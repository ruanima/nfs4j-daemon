package world.gfi.nfs4j.fs;

import org.dcache.nfs.vfs.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import world.gfi.nfs4j.fs.handle.UniqueHandleGenerator;
import world.gfi.nfs4j.fs.permission.PermissionsMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/**
 * A windows implementation of {@link org.dcache.nfs.vfs.VirtualFileSystem}.
 */
public class WindowsNioFileSystem extends AbstractNioFileSystem<DosFileAttributes> {
    private static final Logger LOG = LoggerFactory.getLogger(WindowsNioFileSystem.class);

    public WindowsNioFileSystem(Path root, PermissionsMapper permissionsMapper,
                                UniqueHandleGenerator handleGenerator, boolean recycleEnabled, int fsIndex) {
        super(root, permissionsMapper, handleGenerator, recycleEnabled, fsIndex);
    }

    @Override
    protected DosFileAttributes getFileAttributes(Path path) throws IOException {
        path = handleRegistry.getNFDOrigin(path);
        return Files.getFileAttributeView(path, DosFileAttributeView.class, NOFOLLOW_LINKS).readAttributes();
    }

    @Override
    protected void applyFileAttributesToStat(Stat stat, Path path, DosFileAttributes attrs) throws IOException {
        path = handleRegistry.getNFDOrigin(path);
        super.applyFileAttributesToStat(stat, path, attrs);
        stat.setNlink(1);

        if (Files.isSymbolicLink(path)) {
            this.applySymbolicLinkAttributesToStat(stat, path, attrs);
        }
    }

    /**
     * To be consistent with a native filesystem, Symbolic links size should match the content of the link.
     */
    protected void applySymbolicLinkAttributesToStat(Stat stat, Path path, DosFileAttributes attrs) throws IOException {
        String linkData = this.readlinkFromPath(path);
        stat.setSize(linkData.length());
    }

    @Override
    public boolean getCaseInsensitive() {
        return false;
    }

    @Override
    public boolean getCasePreserving() {
        return false;
    }
}
