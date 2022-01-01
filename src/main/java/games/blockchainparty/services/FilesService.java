package games.blockchainparty.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
@Slf4j
public class FilesService {

    private final Path root = Paths.get("data");

    public FilesService() {
        try {
            if (!Files.exists(root)) {
                Files.createDirectory(root);
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize root folder!");
        }
    }

    public void writeToFile(String filename, String content) {
        try {
            Path path = root.resolve(filename);
            Files.writeString(path, content);
        } catch (IOException e) {
            log.error(e.getMessage(),e);
        }
    }

    public String read(String filename) {
        try {
            if (exists(filename)) {
                Path file = root.resolve(filename);
                Resource resource = new UrlResource(file.toUri());
                return new String(Files.readAllBytes(resource.getFile().toPath()));
            } else {
                throw new RuntimeException("Could not read the file!");
            }
        } catch (IOException e) {
            throw new RuntimeException("Error: " + e.getMessage());
        }
    }

    public void deleteAll() {
        FileSystemUtils.deleteRecursively(root.toFile());
    }

    public boolean exists(String filename) {
        try {
            Path file = root.resolve(filename);
            Resource resource = new UrlResource(file.toUri());
            if (resource.exists() || resource.isReadable()) {
                return true;
            }
        } catch (MalformedURLException e) {
            log.error(e.getMessage(), e);
        }
        return false;
    }
}