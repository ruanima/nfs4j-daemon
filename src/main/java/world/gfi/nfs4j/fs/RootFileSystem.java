package world.gfi.nfs4j.fs;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.dcache.nfs.v4.NfsIdMapping;
import org.dcache.nfs.v4.xdr.nfsace4;
import org.dcache.nfs.vfs.AclCheckable;
import org.dcache.nfs.vfs.DirectoryEntry;
import org.dcache.nfs.vfs.DirectoryStream;
import org.dcache.nfs.vfs.FsStat;
import org.dcache.nfs.vfs.Inode;
import org.dcache.nfs.vfs.Stat;
import org.dcache.nfs.vfs.VirtualFileSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import world.gfi.nfs4j.config.PermissionsConfig;
import world.gfi.nfs4j.exceptions.AliasAlreadyExistsException;
import world.gfi.nfs4j.exceptions.AlreadyAttachedException;
import world.gfi.nfs4j.exceptions.AttachException;
import world.gfi.nfs4j.exceptions.NoSuchAliasException;
import world.gfi.nfs4j.fs.handle.UniqueHandleGenerator;
import world.gfi.nfs4j.fs.permission.LinuxPermissionsSimpleReader;
import world.gfi.nfs4j.fs.permission.SimplePermissionsMapperRead;

import javax.security.auth.Subject;
import java.io.IOError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A memory implementation of {@link VirtualFileSystem} that supports attaching others file systems {@link AttachableFileSystem} on given aliases.
 */
public class RootFileSystem implements VirtualFileSystem {
    private Logger LOG = LoggerFactory.getLogger(RootFileSystem.class);

    private final LinuxNioFileSystem mainFs;
    private Map<String, AttachableFileSystem> fileSystems = new LinkedHashMap<>();

    private static Path buildRootPath() {
        return Jimfs.newFileSystem(
                Configuration.unix().toBuilder()
                        .setAttributeViews("basic", "owner", "posix", "unix")
                        .setWorkingDirectory("/")
                        .build())
                .getRootDirectories().iterator().next();
    }

    public RootFileSystem(PermissionsConfig permissions, UniqueHandleGenerator uniqueLongGenerator) {
        mainFs = new LinuxNioFileSystem(buildRootPath(), new SimplePermissionsMapperRead(new LinuxPermissionsSimpleReader(permissions)), uniqueLongGenerator);
    }

    public AttachableFileSystem attachFileSystem(AttachableFileSystem fs, String path, String... morePath) throws AttachException {
        try {
            if (fs.getAlias() != null) {
                throw new AlreadyAttachedException(fs);
            }
            Path aliasPath = mainFs.root.getFileSystem().getPath(path, morePath);
            Path directories = Files.createDirectories(aliasPath).normalize();
            if (fileSystems.containsKey(directories.toString())) {
                throw new AliasAlreadyExistsException(directories.toString(), fileSystems.get(directories.toString()), fs);
            }
            fileSystems.put(directories.toString(), fs);
            fs.setAlias(directories.toString());
            return fileSystems.get(directories.toString());
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public Map<String, AttachableFileSystem> getFileSystems() {
        return Collections.unmodifiableMap(fileSystems);
    }

    public AttachableFileSystem detachFileSystem(String path, String... morePath) throws AttachException {
        Path aliasPath = mainFs.root.getFileSystem().getPath(path, morePath).normalize();
        AttachableFileSystem removedFileSystem = fileSystems.remove(aliasPath.toString());
        if (removedFileSystem == null) {
            throw new NoSuchAliasException("No FileSystem found for alias " + aliasPath.toString());
        }
        try {
            removedFileSystem.close();
        } catch (IOException e) {
            LOG.error("An error has occured while releasing resources associated to removed FileSystem", e);
        }
        return removedFileSystem;
    }

    protected AttachableFileSystem delegate(Inode inode) {
        for (AttachableFileSystem fs : fileSystems.values()) {
            if (fs.hasInode(inode)) {
                return fs;
            }
        }
        return mainFs;
    }

    @Override
    public Inode getRootInode() throws IOException {
        Inode rootInode = mainFs.handleRegistry.toInode(mainFs.rootFileHandle);
        Path path = mainFs.handleRegistry.toPath(rootInode);
        VirtualFileSystem fs = fileSystems.get(path.toString());
        if (fs != null) {
            return fs.getRootInode();
        }
        return rootInode;
    }

    @Override
    public Inode lookup(Inode parent, String name) throws IOException {
        AttachableFileSystem delegate = delegate(parent);
        if (delegate == mainFs) {
            Path parentPath = mainFs.handleRegistry.toPath(parent);
            Path path = parentPath.resolve(name).normalize();
            VirtualFileSystem fs = fileSystems.get(path.toString());
            if (fs != null) {
                return fs.getRootInode();
            }
        }
        return delegate.lookup(parent, name);
    }

    @Override
    public DirectoryStream list(Inode inode, byte[] verifier, long cookie) throws IOException {
        AttachableFileSystem delegate = delegate(inode);
        if (delegate == mainFs) {
            Path path = mainFs.handleRegistry.toPath(inode);
            final List<DirectoryEntry> list = new ArrayList<>();
            long verifierLong = Long.MIN_VALUE;
            try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(path)) {
                long currentCookie = 0;
                for (Path p : ds) {
                    String filename = p.getFileName().toString();
                    verifierLong += filename.hashCode() + currentCookie * 1024;
                    if ((cookie == 0 && currentCookie >= cookie) || (cookie > 0 && currentCookie > cookie)) {
                        AttachableFileSystem fs = fileSystems.get(p.normalize().toString());
                        if (fs == null) {
                            list.add(mainFs.buildDirectoryEntry(p, currentCookie));
                        } else {
                            list.add(fs.buildRootDirectoryEntry(filename, currentCookie));
                        }
                    }
                    currentCookie++;
                }
            }
            return new DirectoryStream(mainFs.toVerifier(verifierLong), list);
        } else {
            return delegate.list(inode, verifier, cookie);
        }
    }

    @Override
    public Stat getattr(Inode inode) throws IOException {
        return delegate(inode).getattr(inode);
    }

    @Override
    public int access(Inode inode, int mode) throws IOException {
        return delegate(inode).access(inode, mode);
    }

    @Override
    public Inode create(Inode parent, Stat.Type type, String path, Subject subject, int mode) throws IOException {
        return delegate(parent).create(parent, type, path, subject, mode);
    }

    @Override
    public FsStat getFsStat() throws IOException {
        return fileSystems.values().iterator().next().getFsStat();
    }

    @Override
    public Inode link(Inode parent, Inode existing, String target, Subject subject) throws IOException {
        return delegate(parent).link(parent, existing, target, subject);
    }

    @Override
    public byte[] directoryVerifier(Inode inode) throws IOException {
        return delegate(inode).directoryVerifier(inode);
    }

    @Override
    public Inode mkdir(Inode parent, String path, Subject subject, int mode) throws IOException {
        return delegate(parent).mkdir(parent, path, subject, mode);
    }

    @Override
    public boolean move(Inode src, String oldName, Inode dest, String newName) throws IOException {
        return delegate(src).move(src, oldName, dest, newName);
    }

    @Override
    public Inode parentOf(Inode inode) throws IOException {
        return delegate(inode).parentOf(inode);
    }

    @Override
    public String readlink(Inode inode) throws IOException {
        return delegate(inode).readlink(inode);
    }

    @Override
    public void remove(Inode parent, String path) throws IOException {
        delegate(parent).remove(parent, path);
    }

    @Override
    public Inode symlink(Inode parent, String linkName, String targetName, Subject subject, int mode) throws IOException {
        return delegate(parent).symlink(parent, linkName, targetName, subject, mode);
    }

    @Override
    public int read(Inode inode, byte[] data, long offset, int count) throws IOException {
        return delegate(inode).read(inode, data, offset, count);
    }

    @Override
    public WriteResult write(Inode inode, byte[] data, long offset, int count, StabilityLevel stabilityLevel) throws IOException {
        return delegate(inode).write(inode, data, offset, count, stabilityLevel);
    }

    @Override
    public void commit(Inode inode, long offset, int count) throws IOException {
        delegate(inode).commit(inode, offset, count);
    }

    @Override
    public void setattr(Inode inode, Stat stat) throws IOException {
        delegate(inode).setattr(inode, stat);
    }

    @Override
    public nfsace4[] getAcl(Inode inode) throws IOException {
        return delegate(inode).getAcl(inode);
    }

    @Override
    public void setAcl(Inode inode, nfsace4[] acl) throws IOException {
        delegate(inode).setAcl(inode, acl);
    }

    @Override
    public boolean hasIOLayout(Inode inode) throws IOException {
        return delegate(inode).hasIOLayout(inode);
    }

    @Override
    public AclCheckable getAclCheckable() {
        return mainFs.getAclCheckable();
    }

    @Override
    public NfsIdMapping getIdMapper() {
        return mainFs.getIdMapper();
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
