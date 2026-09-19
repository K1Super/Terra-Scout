package com.terrascout.orchestrator.app.startup;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import com.terrascout.orchestrator.core.constant.PathConstants;

/**
 * 启动期引导：数据根目录树 / 数据库备份 / 数据库密码持久化。
 *
 * <p>本类在 {@code main()} 中、Spring 上下文启动前执行，保证：
 * <ul>
 *   <li>数据根与全部子目录存在（db / logs / config / sdks / db\backup / diagnostics）；</li>
 *   <li>已存在的 H2 文件库在建立任何连接前被复制到备份目录（一致性安全）；</li>
 *   <li>{@code db.properties} 不存在则随机生成密码并持久化，ACL 仅当前用户。</li>
 * </ul>
 */
public final class StartupGuard {

    /** 生日格式：yyyyMMddHHmmss（备份文件名后缀）。 */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final SecureRandom RANDOM = new SecureRandom();

    private StartupGuard() {
    }

    /** 准备数据根目录树，并在有既有文件库时执行启动备份。 */
    public static void prepareDataRoot() {
        Path dataRoot = PathConstants.dataRoot();
        createDirs(dataRoot);
        backupExistingDb(dataRoot);
    }

    /** 确保数据库密码已持久化到 db.properties；已存在则复用。 */
    public static String ensureDatabasePassword() {
        Path root = PathConstants.dataRoot();
        createDirs(root.resolve(PathConstants.DIR_CONFIG));
        Path file = PathConstants.dbProperties(root);
        if (Files.exists(file)) {
            return readPassword(file);
        }
        String password = randomPassword();
        writePassword(file, password);
        return password;
    }

    /** 读取已存在的 db.properties 中的密码。 */
    private static String readPassword(Path file) {
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = trimmed.substring(0, eq).trim();
                if (key.equals("terrascout.db.password")) {
                    return trimmed.substring(eq + 1).trim();
                }
            }
            return null;
        } catch (Exception e) {
            throw new IllegalStateException("读取数据库密码文件失败: " + file, e);
        }
    }

    private static void writePassword(Path file, String password) {
        try {
            Files.writeString(file, "terrascout.db.password=" + password + System.lineSeparator());
            restrictAcl(file);
        } catch (Exception e) {
            throw new IllegalStateException("写入数据库密码文件失败: " + file, e);
        }
    }

    /** 生成 24 字节随机密码并 Base64。 */
    private static String randomPassword() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * 启动备份：复制 {data-root}/db/terrascout.mv.db → {data-root}/db/backup/terra-scout-{stamp}.mv.db。
     * 无既有文件库时静默跳过（首次启动无库可备）。
     */
    private static void backupExistingDb(Path dataRoot) {
        Path src = PathConstants.databaseFile(dataRoot);
        if (!Files.exists(src)) {
            return;
        }
        try {
            Path backupDir = dataRoot.resolve(PathConstants.DIR_DB)
                    .resolve(PathConstants.DIR_BACKUP);
            createDirs(backupDir);
            String stamp = LocalDateTime.now().format(STAMP);
            Path dest = backupDir.resolve("terra-scout-" + stamp + PathConstants.BACKUP_SUFFIX);
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IllegalStateException("启动备份失败，中止启动以免破坏数据库: " + src, e);
        }
    }

    private static void createDirs(Path dir) {
        try {
            Files.createDirectories(dir);
            // 数据根下全部子目录
            Path root = dir;
            Files.createDirectories(root.resolve(PathConstants.DIR_DB)
                    .resolve(PathConstants.DIR_BACKUP));
            Files.createDirectories(root.resolve(PathConstants.DIR_LOGS));
            Files.createDirectories(root.resolve(PathConstants.DIR_CONFIG));
            Files.createDirectories(root.resolve(PathConstants.DIR_SDKS));
            Files.createDirectories(root.resolve(PathConstants.DIR_DIAGNOSTICS));
        } catch (Exception e) {
            throw new IllegalStateException("创建数据根目录失败: " + dir, e);
        }
    }

    /**
     * 收紧密码文件 ACL：确保当前用户可读可写（等效 chmod 600）。
     *
     * <p>采用<b>追加</b>而非替换：保留文件既有（含继承）条目，并为 owner 补一条完整读写
     * ALLOW。NIO {@link AclFileAttributeView} 在 Windows 上整体替换会丢失继承 ACE，
     * 导致该文件对当前用户不可读；追加可同时满足安全目标与可访问性。
     */
    private static void restrictAcl(Path file) {
        try {
            AclFileAttributeView view =
                    Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (view == null) {
                return;
            }
            java.nio.file.attribute.UserPrincipal owner = view.getOwner();
            List<AclEntry> entries = new ArrayList<>(view.getAcl());
            boolean hasOwnerAllow = entries.stream().anyMatch(e -> e.type() == AclEntryType.ALLOW
                    && owner.equals(e.principal()) && e.permissions().containsAll(FULL));
            if (!hasOwnerAllow) {
                entries.add(AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(owner)
                        .setPermissions(FULL)
                        .build());
            }
            view.setAcl(entries);
        } catch (NoSuchFileException e) {
            throw new IllegalStateException(e);
        } catch (Exception e) {
            // 不支持 ACL 的平台静默降级，密码文件仍为普通文件
        }
    }

    /** owner 完整权限集合（读写 + ACL 管理，等效 chmod 600 的 owner 位）。 */
    private static final Set<AclEntryPermission> FULL = Set.of(
            AclEntryPermission.READ_DATA,
            AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA,
            AclEntryPermission.READ_NAMED_ATTRS,
            AclEntryPermission.WRITE_NAMED_ATTRS,
            AclEntryPermission.EXECUTE,
            AclEntryPermission.DELETE_CHILD,
            AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.WRITE_ATTRIBUTES,
            AclEntryPermission.DELETE,
            AclEntryPermission.READ_ACL,
            AclEntryPermission.WRITE_ACL,
            AclEntryPermission.WRITE_OWNER,
            AclEntryPermission.SYNCHRONIZE);
}
