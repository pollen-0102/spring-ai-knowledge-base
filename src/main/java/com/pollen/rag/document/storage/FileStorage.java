package com.pollen.rag.document.storage;

import com.pollen.rag.common.BizException;
import com.pollen.rag.common.ErrorCode;
import com.pollen.rag.config.RagProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
public class FileStorage {

    private static final Set<String> ALLOWED = Set.of("PDF", "DOCX", "MD", "TXT");

    private final RagProperties props;

    public FileStorage(RagProperties props) {
        this.props = props;
    }

    public StoredFile save(MultipartFile file, String originalName) {
        String name = basename(originalName);
        String fileType = extension(name);
        if (!ALLOWED.contains(fileType)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "仅支持 PDF / DOCX / MD / TXT，当前文件: " + fileType);
        }
        if (file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件为空");
        }
        Path dir = Paths.get(props.getStorage().getUploadDir()).toAbsolutePath().normalize();
        try {
            extracted(dir);
            String safeName = sanitize(name);
            Path target = dir.resolve(UUID.randomUUID() + "-" + safeName);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new StoredFile(target.toString(), name, fileType, Files.size(target), sha256(target));
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "保存文件失败: " + e.getMessage(), e);
        }
    }

    private static void extracted(Path dir) throws IOException {
        Files.createDirectories(dir);
    }

    public Path path(String storagePath) {
        return Paths.get(storagePath).toAbsolutePath().normalize();
    }

    public void delete(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(Paths.get(storagePath));
        } catch (IOException ignored) {
            // best effort
        }
    }

    private String basename(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "unnamed";
        }
        String name = originalName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.trim();
        if (name.length() > 255) {
            String ext = extension(name);
            int keep = Math.max(1, 250 - ext.length());
            name = name.substring(0, keep) + "." + ext.toLowerCase(Locale.ROOT);
        }
        return name.isBlank() ? "unnamed" : name;
    }

    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "UNKNOWN";
        }
        return name.substring(dot + 1).toUpperCase(Locale.ROOT);
    }

    private String sanitize(String name) {
        String cleaned = name.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
        if (cleaned.length() > 120) {
            String ext = extension(cleaned);
            int keep = Math.max(1, 120 - ext.length() - 1);
            cleaned = cleaned.substring(0, keep) + "." + ext.toLowerCase(Locale.ROOT);
        }
        return cleaned.isBlank() ? "file" : cleaned;
    }

    private String sha256(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
