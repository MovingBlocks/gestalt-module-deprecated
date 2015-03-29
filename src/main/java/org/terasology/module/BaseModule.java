/*
 * Copyright 2014 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.terasology.module;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeAnnotationsScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.module.filesystem.ModuleFileSystemProvider;
import org.terasology.naming.Name;
import org.terasology.naming.Version;
import org.terasology.util.io.FileScanning;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Collection;
import java.util.Objects;

/**
 * Common base abstract class for modules.
 *
 * @author Immortius
 */
public abstract class BaseModule implements Module {
    private static final String REFLECTIONS_CACHE_FILE = "reflections.cache";
    private static final Logger logger = LoggerFactory.getLogger(BaseModule.class);

    protected ImmutableList<Path> paths;
    protected ModuleMetadata metadata;

    private Reflections reflectionsFragment;
    private final FileSystem fileSystem;

    /**
     * @param paths    The paths composing the module
     * @param metadata The metadata describing the module
     */
    public BaseModule(Collection<Path> paths, ModuleMetadata metadata) {
        this.paths = ImmutableList.copyOf(paths);
        this.metadata = metadata;
        try {
            this.fileSystem = new ModuleFileSystemProvider(this).newFileSystem(this);
        } catch (IOException e) {
            throw new RuntimeException("Error creating module '" + metadata.getId() + ":" + metadata.getVersion() + "'", e);
        }
    }

    @Override
    public ImmutableList<Path> getLocations() {
        return paths;
    }

    @Override
    public FileSystem getFileSystem() {
        return fileSystem;
    }

    @Override
    public ImmutableList<Path> findFiles() throws IOException {
        return findFiles(getFileSystem().getPath(ModuleFileSystemProvider.ROOT), FileScanning.acceptAll(), FileScanning.acceptAll());
    }

    @Override
    public ImmutableList<Path> findFiles(String fileFilterGlob) throws IOException {
        PathMatcher globFilter = getFileSystem().getPathMatcher(fileFilterGlob);
        return ImmutableList.copyOf(FileScanning.findFilesInPath(getFileSystem().getPath(ModuleFileSystemProvider.ROOT), FileScanning.acceptAll(), globFilter));
    }

    @Override
    public ImmutableList<Path> findFiles(Path path, PathMatcher scanFilter, PathMatcher fileFilter) throws IOException {
        return ImmutableList.copyOf(FileScanning.findFilesInPath(path, scanFilter, fileFilter));
    }

    @Override
    public Name getId() {
        return metadata.getId();
    }

    @Override
    public Version getVersion() {
        return metadata.getVersion();
    }

    @Override
    public ImmutableSet<String> getRequiredPermissions() {
        return ImmutableSet.copyOf(metadata.getRequiredPermissions());
    }

    @Override
    public ModuleMetadata getMetadata() {
        return metadata;
    }

    @Override
    public Reflections getReflectionsFragment() {
        Preconditions.checkState(isCodeModule(), "Cannot get reflections fragment for non-code module");
        if (reflectionsFragment == null) {
            for (Path path : paths) {
                if (Files.isDirectory(path) && Files.isRegularFile(path.resolve(REFLECTIONS_CACHE_FILE))) {
                    collectCacheFromPath(path);
                } else if (Files.isRegularFile(path)) {
                    collectCacheFromArchive(path);
                }
            }
            if (reflectionsFragment == null) {
                scanForReflections();
            }
        }
        return reflectionsFragment;
    }

    private void scanForReflections() {
        reflectionsFragment = new Reflections(new ConfigurationBuilder()
                .addUrls(getClasspaths())
                .setScanners(new TypeAnnotationsScanner(), new SubTypesScanner())
                .addClassLoader(ClasspathHelper.staticClassLoader()));
    }

    private void collectCacheFromArchive(Path path) {
        try (FileSystem archive = FileSystems.newFileSystem(path, null)) {
            Path cachePath = archive.getPath(REFLECTIONS_CACHE_FILE);
            if (Files.isRegularFile(cachePath)) {
                if (reflectionsFragment == null) {
                    try (InputStream stream = new BufferedInputStream(Files.newInputStream(cachePath))) {
                        reflectionsFragment = new ConfigurationBuilder().getSerializer().read(stream);
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Failure attempting to read reflections cache from {}", this, e);
        }
    }

    private void collectCacheFromPath(Path path) {
        if (reflectionsFragment == null) {
            reflectionsFragment = new Reflections(new ConfigurationBuilder().addClassLoader(ClasspathHelper.staticClassLoader()));
        }
        reflectionsFragment.collect(path.resolve(REFLECTIONS_CACHE_FILE).toFile());
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj instanceof BaseModule) {
            BaseModule other = (BaseModule) obj;
            return Objects.equals(other.getId(), getId()) && Objects.equals(other.getVersion(), getVersion());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(getId(), getVersion());
    }

    @Override
    public String toString() {
        return metadata.getId() + "-" + metadata.getVersion();
    }
}
