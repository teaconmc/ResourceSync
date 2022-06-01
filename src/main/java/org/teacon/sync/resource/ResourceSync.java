/*
 * Copyright (C) 2021 3TUSK
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

// SPDX-Identifier: LGPL-2.1-or-later

package org.teacon.sync.resource;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.WritingMode;
import com.google.common.base.Suppliers;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.cache.HttpCacheContext;
import org.apache.http.client.cache.HttpCacheEntry;
import org.apache.http.client.cache.HttpCacheStorage;
import org.apache.http.client.cache.HttpCacheUpdateCallback;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.cache.CacheConfig;
import org.apache.http.impl.client.cache.CachingHttpClients;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Mod("resource_sync")
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public final class ResourceSync {

    public static final Logger LOGGER = LogManager.getLogger("ResourceSync");

    public static ForgeConfigSpec.ConfigValue<String> packURL;

    public ResourceSync() {
        // Client-side only
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> "ANY", (serverVer, isDedicated) -> true));
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ClientSetup::setup);
    }

    @MethodsReturnNonnullByDefault
    @ParametersAreNonnullByDefault
    static final class ClientSetup {

        private static final Marker SETUP_MARKER = MarkerManager.getMarker("Setup");

        private static final Supplier<HttpCacheStorage> storage = Suppliers.memoize(() -> {
            try {
                var dir = Minecraft.getInstance().gameDirectory.toPath().resolve("resource-sync");
                var cacheFile = Files.createDirectories(dir).resolve("cache.bin");
                return new SingleFileCacheStorage(cacheFile);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        private static final Supplier<Path> dstPath = Suppliers.memoize(() -> {
            try {
                var dir = Minecraft.getInstance().gameDirectory.toPath().resolve("resource-sync");
                return Files.createDirectories(dir).resolve("resources.zip");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        private static CompletableFuture<Void> downloadTask = CompletableFuture.completedFuture(null);

        private static void forceLoad(ForgeConfigSpec configSpec, Path configPath) {
            var configData = CommentedFileConfig.builder(configPath)
                    .sync().preserveInsertionOrder().autosave().writingMode(WritingMode.REPLACE).build();

            LOGGER.debug(SETUP_MARKER, "Built TOML config for {}", configPath);
            configData.load();

            LOGGER.debug(SETUP_MARKER, "Loaded TOML config file {}", configPath);
            configSpec.setConfig(configData);
            configSpec.save();
        }

        public static void setup() {
            var configSpecBuilder = new ForgeConfigSpec.Builder();

            var def = "http://example.invalid";
            var comment = " URL pointing to resource pack";
            packURL = configSpecBuilder.comment(comment).define("packURL", def);
            forceLoad(configSpecBuilder.build(), FMLPaths.CONFIGDIR.get().resolve("resource-sync-client.toml"));

            Minecraft.getInstance().getResourcePackRepository().addPackFinder(new SyncedPackFinder(dstPath.get()));
        }

        public static CompletableFuture<Void> fetchDownloadTask() {
            if (downloadTask.isDone()) {
                downloadTask = CompletableFuture.runAsync(new DownloadTask(storage.get(), dstPath.get()));
            }
            return downloadTask;
        }
    }

    @MethodsReturnNonnullByDefault
    @ParametersAreNonnullByDefault
    record SyncedPackFinder(Path dstPath) implements RepositorySource {

        private static final Marker MARKER = MarkerManager.getMarker("PackFinder");

        @Override
        public void loadPacks(Consumer<Pack> callback, Pack.PackConstructor factory) {
            this.waitForDownloadTask();
            this.loadResources(factory).thenAccept(callback).exceptionally(error -> {
                LOGGER.warn(MARKER, error.getMessage(), error);
                return null;
            });
        }

        private CompletableFuture<Pack> loadResources(Pack.PackConstructor packInfoFactory) {
            var future = new CompletableFuture<Pack>();
            var pack = Pack.create("resource_sync", true,
                    () -> new ResourcePack(this.dstPath), packInfoFactory, Pack.Position.TOP, PackSource.BUILT_IN);
            if (pack == null) {
                var e = new RuntimeException("An error was thrown when loading the resource pack: " + this.dstPath);
                future.completeExceptionally(e);
            } else {
                future.complete(pack);
            }
            return future;
        }

        private void waitForDownloadTask() {
            try {
                ClientSetup.fetchDownloadTask().join();
            } catch (CompletionException e) {
                LOGGER.warn(MARKER, "An error was thrown when finding the resource pack.", e);
                LOGGER.warn(MARKER, "ResourceSync will try to load the resource pack locally: {}", this.dstPath);
            }
        }

        @MethodsReturnNonnullByDefault
        @ParametersAreNonnullByDefault
        private static final class ResourcePack extends FilePackResources {
            public ResourcePack(Path target) {
                super(target.toFile());
            }

            @Override
            public String getName() {
                return "Resource Sync";
            }
        }
    }

    @MethodsReturnNonnullByDefault
    @ParametersAreNonnullByDefault
    static final class DownloadTask implements Runnable {

        private static final Marker MARKER = MarkerManager.getMarker("Download");
        private static final CacheConfig CONFIG = CacheConfig.custom()
                .setMaxObjectSize(Integer.MAX_VALUE).setSharedCache(false).build();

        private final Path dstPath;
        private final String currentPackURL;
        private final CloseableHttpClient client;

        DownloadTask(HttpCacheStorage cacheStorage, Path dstPath) {
            this.dstPath = dstPath;
            this.currentPackURL = packURL.get();
            this.client = CachingHttpClients.custom().setCacheConfig(CONFIG).setHttpCacheStorage(cacheStorage).build();
        }

        @Override
        public void run() {
            var request = new HttpGet(this.currentPackURL);
            var context = HttpCacheContext.create();
            try (var response = this.client.execute(request, context)) {
                LOGGER.debug(MARKER, " >> {}", context.getRequest().getRequestLine());
                for (var header : context.getRequest().getAllHeaders()) {
                    LOGGER.debug(MARKER, " >> {}", header);
                }
                LOGGER.debug(MARKER, " << {}", context.getResponse().getStatusLine());
                for (var header : context.getResponse().getAllHeaders()) {
                    LOGGER.debug(MARKER, " << {}", header);
                }
                LOGGER.debug(MARKER, "Remote server status: {}", context.getCacheResponseStatus());
                try {
                    var tempDst = Files.createTempFile("synced-pack-", ".zip");
                    response.getEntity().writeTo(Files.newOutputStream(tempDst));
                    Files.move(tempDst, this.dstPath, StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.info(MARKER, "Downloaded resource pack from (or found in cache): {}", request.getURI());
                } catch (IOException readError) {
                    LOGGER.warn(MARKER, "Error occurred while downloading " +
                            "the resource pack from remote server. Download cannot continue.", readError);
                    throw new CompletionException(readError);
                }
            } catch (ClientProtocolException protocolError) {
                LOGGER.warn(MARKER, "Detected invalid client protocol, " +
                        "ResourceSync will not try download resource pack this time.", protocolError);
                throw new CompletionException(protocolError);
            } catch (IOException connError) {
                LOGGER.warn(MARKER, "Failed to establish connection, " +
                        "ResourceSync will not try download resource pack this time.", connError);
                throw new CompletionException(connError);
            }
        }
    }

    @MethodsReturnNonnullByDefault
    @ParametersAreNonnullByDefault
    static final class SingleFileCacheStorage implements HttpCacheStorage {

        private final Path path;
        private volatile UUID key;

        SingleFileCacheStorage(Path path) {
            this.path = path;
            try (var stream = new ObjectInputStream(Files.newInputStream(path))) {
                this.key = (UUID) stream.readObject();
            } catch (ClassCastException | ClassNotFoundException | IOException e) {
                this.key = new UUID(0L, 0L);
            }
        }

        private void setEntry(UUID uuid, @Nullable HttpCacheEntry entry) throws IOException {
            if (entry != null) {
                try (var stream = new ObjectOutputStream(Files.newOutputStream(this.path))) {
                    stream.writeObject(this.key = uuid);
                    stream.writeObject(entry);
                }
            } else if (this.key.equals(uuid)) {
                this.key = new UUID(0L, 0L);
                Files.delete(this.path);
            }
        }

        @Nullable
        private HttpCacheEntry getEntry(UUID uuid) throws IOException {
            if (this.key.equals(uuid)) {
                try (var stream = new ObjectInputStream(Files.newInputStream(this.path))) {
                    var object = stream.readObject();
                    if (this.key.equals(object)) {
                        return (HttpCacheEntry) stream.readObject();
                    }
                } catch (ClassCastException | ClassNotFoundException e) {
                    throw new IOException(e);
                }
            }
            return null;
        }

        @Override
        @Nullable
        public HttpCacheEntry getEntry(String entryKey) throws IOException {
            var uuid = UUID.nameUUIDFromBytes(entryKey.getBytes(StandardCharsets.UTF_8));
            synchronized (this) {
                return this.getEntry(uuid);
            }
        }

        @Override
        public void putEntry(String entryKey, HttpCacheEntry entry) throws IOException {
            var uuid = UUID.nameUUIDFromBytes(entryKey.getBytes(StandardCharsets.UTF_8));
            synchronized (this) {
                this.setEntry(uuid, entry);
            }
        }

        @Override
        public void removeEntry(String entryKey) throws IOException {
            var uuid = UUID.nameUUIDFromBytes(entryKey.getBytes(StandardCharsets.UTF_8));
            synchronized (this) {
                this.setEntry(uuid, null);
            }
        }

        @Override
        public void updateEntry(String entryKey, HttpCacheUpdateCallback callback) throws IOException {
            var uuid = UUID.nameUUIDFromBytes(entryKey.getBytes(StandardCharsets.UTF_8));
            synchronized (this) {
                this.setEntry(uuid, callback.update(this.getEntry(uuid)));
            }
        }
    }
}
