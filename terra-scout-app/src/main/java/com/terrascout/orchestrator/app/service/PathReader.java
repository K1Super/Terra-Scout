package com.terrascout.orchestrator.app.service;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * 文件系统访问注入缝（P2-1）：探测类的全部文件副作用经此接口，单测可注入内存实现。
 * 生产默认 {@link #DEFAULT}（直接委托 {@link Files}）。
 */
interface PathReader {

    List<String> readAllLines(Path path, Charset charset) throws IOException;

    String readString(Path path, Charset charset) throws IOException;

    boolean isRegularFile(Path path);

    boolean isDirectory(Path path);

    Stream<Path> list(Path dir) throws IOException;

    /** 默认实现：真实文件系统。 */
    PathReader DEFAULT = new PathReader() {
        @Override
        public List<String> readAllLines(Path path, Charset charset) throws IOException {
            return Files.readAllLines(path, charset);
        }

        @Override
        public String readString(Path path, Charset charset) throws IOException {
            return Files.readString(path, charset);
        }

        @Override
        public boolean isRegularFile(Path path) {
            return Files.isRegularFile(path);
        }

        @Override
        public boolean isDirectory(Path path) {
            return Files.isDirectory(path);
        }

        @Override
        public Stream<Path> list(Path dir) throws IOException {
            return Files.list(dir);
        }
    };
}
