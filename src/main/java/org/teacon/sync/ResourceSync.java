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

package org.teacon.sync;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.FilePack;
import net.minecraft.resources.IPackFinder;
import net.minecraft.resources.IPackNameDecorator;
import net.minecraft.resources.ResourcePackInfo;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.network.FMLNetworkConstants;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Mod("resource_sync")
public class ResourceSync {

    private static final Logger LOGGER = LogManager.getLogger("ResourceSync");

    static ForgeConfigSpec.ConfigValue<String> packURL;

    public ResourceSync() {
        ModLoadingContext context = ModLoadingContext.get();
        // Client-side only
        context.registerExtensionPoint(ExtensionPoint.DISPLAYTEST, () -> Pair.of(() -> FMLNetworkConstants.IGNORESERVERONLY, (serverVer, isDedicated) -> true));
        ForgeConfigSpec.Builder configSpecBuilder = new ForgeConfigSpec.Builder();
        packURL = configSpecBuilder.comment("URL pointing to resource pack").define("packURL", "http://example.invalid");
        context.registerConfig(ModConfig.Type.CLIENT, configSpecBuilder.build());
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> Setup::setup);
    }

    static final class Setup {

        static CompletableFuture<Void> downloadTask = CompletableFuture.completedFuture(null);

        static void setup() {
            final File gameDir = Minecraft.getInstance().gameDirectory;
            final Path dst = gameDir.toPath().resolve("resourcepacks").resolve(".resource_sync.zip");
            downloadTask = CompletableFuture.runAsync(new DownloadTask(dst));
            Minecraft.getInstance().getResourcePackRepository().addPackFinder(new SyncedPackFinder(dst.toFile()));
        }
    }

    public static final class SyncedPackFinder implements IPackFinder {

        private static final Marker PACK_FINDER_MARKER = MarkerManager.getMarker("PackFinder");

        private final File searchTarget;

        public SyncedPackFinder(File searchTarget) {
            this.searchTarget = searchTarget;
        }

        @Override
        public void loadPacks(Consumer<ResourcePackInfo> packInfoCallback, ResourcePackInfo.IFactory packInfoFactory) {
            try {
                Setup.downloadTask.join();
            } catch (Exception e) {
                LOGGER.warn(PACK_FINDER_MARKER, "Uncaught occured while trying to fetch resource pack. ResourceSync will not try load any resource pack.", e);
                return;
            }
            if (this.searchTarget.exists() && this.searchTarget.isFile()) {
                final ResourcePackInfo packInfo = ResourcePackInfo.create("ResourceSync", true, () -> new FilePack(this.searchTarget), 
                        packInfoFactory, ResourcePackInfo.Priority.TOP, IPackNameDecorator.BUILT_IN);
                packInfoCallback.accept(packInfo);
            } else {
                LOGGER.warn(PACK_FINDER_MARKER, "The synced resource pack is missing; ResourceSync will not try load any resource pack.");
            }
        }
    }

    static final class DownloadTask implements Runnable {

        private static final Marker DOWNLOAD_MARKER = MarkerManager.getMarker("Download");

        private final Path dst;

        DownloadTask(Path dst) {
            this.dst = dst;
        }

        @Override
        public void run() {
            try {
                URL url = new URL(packURL.get());
                URLConnection conn = url.openConnection();
                conn.setIfModifiedSince(System.currentTimeMillis());
                if (conn instanceof HttpURLConnection) {
                    ((HttpURLConnection) conn).setInstanceFollowRedirects(true);
                }
                conn.connect();
                if (conn instanceof HttpURLConnection) {
                    if (((HttpURLConnection) conn).getResponseCode() == HttpURLConnection.HTTP_NOT_MODIFIED) {
                        LOGGER.debug(DOWNLOAD_MARKER, "Remote server tells us that we can use our local copy (HTTP 304), abort downloading.");
                        return;
                    }
                }
                final Path tempDst = Files.createTempFile("synced_pack-", ".zip");
                try (ReadableByteChannel srcChannel = Channels.newChannel(conn.getInputStream())) {
                    try (FileChannel dstChannel = FileChannel.open(tempDst)) {
                        dstChannel.transferFrom(srcChannel, 0, Integer.MAX_VALUE);
                        Files.move(tempDst, this.dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    }
                } catch (IOException readError) {
                    LOGGER.warn(DOWNLOAD_MARKER, "Error occurred while downloading the resource pack from remote server. Download cannot continue.", readError);
                }
            } catch (MalformedURLException badURL) {
                LOGGER.warn(DOWNLOAD_MARKER, "URL invalid, ResourceSync will not try download resource pack this time.");
            } catch (IOException connError) {
                LOGGER.warn(DOWNLOAD_MARKER, "Failed to establish connection, ResourceSync will not try download resource pack this time.", connError);
            }
        }
    }
}
