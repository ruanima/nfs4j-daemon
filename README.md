# nfs4j-daemon

Pure Java NFS v3/v4.1 server backed by [dCache nfs4j](https://github.com/dCache/nfs4j).

This project has been designed as an alternative to [winnfsd](https://github.com/winnfsd/winnfsd). Vagrant plugin is 
available at [gfi-centre-ouest/vagrant-nfs4j](https://github.com/gfi-centre-ouest/vagrant-nfs4j).

*NFS v3 is available, but not untested and unsupported. You should use NFS v4.1 only for now.*

## TODO:

- [ ] MacOS server support.
- [x] ip range based auth
- [x] NFS V3 for macOS client
- [x] recycle bin and hide it from file list
- [x] specify bind address with OncRpcSvcBuilder.withBindAddress
- [ ] map uid gid from mount client rather than config
- [x] GraalVM Native Image


## Quickstart

- Download latest binaries from [Github Releases](https://github.com/ruanimal/nfs4j-daemon/releases).
- Download and install JRE11 from [adoptium.net](https://adoptium.net/zh-CN/temurin/archive/?version=11)
- Run `nfs4j-daemon`. With default options, it will publish the current working directory through NFS.

```bash
java -jar nfs4j-daemon.jar
```

- Windows users may use the `.exe` wrapper.

```bash
nfs4j-daemon.exe
```

- Mount the share on any OS supporting NFS.

```bash
mkdir /mnt/nfs4j
mount -t nfs 192.168.1.1:/ /mnt/nfs4j
```

## Options

All options are available through Command Line and Configuration File.

### Command Line

```bash
java -jar nfs4j-daemon.jar --help
```

```bash
Usage: <main class> [-h] [--api] [--no-share] [--portmap-disabled] [--udp]
                    [--api-bearer=<apiBearer>] [--api-ip=<apiIp>]
                    [--api-port=<apiPort>] [-c=<config>] [-e=<exports>]
                    [-g=<gid>] [-m=<mask>] [-p=<port>] [-t=<permissionType>]
                    [-u=<uid>] [<shares>...]
      [<shares>...]          Directories to share
  -c, --config=<config>      Path to configuration file
  -u, --uid=<uid>            Default user id to use for exported files
  -g, --gid=<gid>            Default group id to use for exported files
  -m, --mask=<mask>          Default mask to use for exported files
  -t, --permission-type=<permissionType>
                             Permission type to use (DISABLED, EMULATED, UNIX)
                               Default: DISABLED
  -p, --port=<port>          Port to use
      --api                  Enable HTTP API
      --api-port=<apiPort>   Port to use for API
      --api-ip=<apiIp>       Ip to use for API
      --api-bearer=<apiBearer>
                             Bearer to use for API authentication
      --no-share             Disable default share and configured shares
      --udp                  Use UDP instead of TCP
      --portmap-disabled     Disable embedded portmap service
      --recycle-enabled      Enable recycle bin, location <share>/.recycle
      --bind=<address>       Server bind address 
  -e, --exports=<exports>    Path to exports file (nsf4j advanced configuration)
  -h, --help                 Display this help message
```

### Configuration File

Configuration file is loaded from *nfs4j.yml* in working directory by default.

You can set a custom filepath to this configuration file with `-c, --config=<config>` command line option.

Use `--exports` option with `--config` is better.
```yaml
port: 2048
udp: false
bindAddress: 192.168.123.10
recycleEnabled: true
permissions:
  gid: 1000
  uid: 1000
  mask: 0644
shares:
  - 'C:\Users\Toilal\projects\planireza:/planireza'
  - 'C:\Users\Toilal\projects\docker-devbox:/docker-devbox'
  - 'D:\:/d'
```

*Make sure you are using single quote on shares definition strings in yaml configuration file to avoid issues 
with backslashes.*

## Shares configuration

- If no share is configured, the current working directory is published under the root alias ```/```.

```bash
# Service side
java -jar nfs4j-daemon.jar
# Client side
mount -t nfs 192.168.1.1:/ /mnt/nfs4j
```

- If a single share is configured, it's published under the root alias ```/```.

```bash
# Service side
java -jar nfs4j-daemon.jar C:\my\folder
# Client side
mount -t nfs 192.168.1.1:/ /mnt/nfs4j
```

- If many shares are configured, they will be aliased automatically based on their local path.

```bash
# Server side
java -jar nfs4j-daemon.jar C:\my\folder D:\another\folder
# Client side
mount -t nfs 192.168.1.1:/C/my/folder /mnt/nfs4j-1
mount -t nfs 192.168.1.1:/D/another/folder /mnt/nfs4j-2
```

- Alias can be defined manually by adding its value after the local path of the share, using 
```:``` as separator.

```
# Service side
java -jar nfs4j-daemon.jar C:\my\folder:/folder1 D:\another\folder:/folder2
# Client side
mount -t nfs 192.168.1.1:/folder1 /mnt/nfs4j-1
mount -t nfs 192.168.1.1:/folder2 /mnt/nfs4j-2
```

- Or using the configuration file, with string syntax.

```
shares:
  - 'C:\my\folder:/folder1'
  - 'D:\another\folder:/folder2'
```

- Or using the configuration file, with object syntax.

```
shares:
  - path: 'C:\my\folder'
    alias: '/folder1'
  - path: 'D:\another\folder'
    alias: '/folder2'
```

- By default, permissions type is set to DISABLED on Windows, and `UNIX` on Linux.

    - `DISABLED` => File permission support is disabled. Best performances, but files will always match default uid, gid and mode (`chown`/`chmod` has no effect).
    - `EMULATED` => File permission support is emulated using a local database. This may impact performance, files uid, gid and mode are preserved on any server OS.
    - `UNIX` => File permission support use native Unix attributes on the server. This better performance than `EMULTAED`, files uid, gid and mode are be preserved, but this option is only supported on Unix servers.

## Exports file configuration
exports file contains advanced configuration for shares, such as ip address based permission.

see also [Ret Hat nfs config document](https://access.redhat.com/documentation/en-us/red_hat_enterprise_linux/5/html/deployment_guide/s1-nfs-server-config-exports)

ip address permission config example 
```
/share 192.168.123.1/24
/share1 192.168.123.1/25
```

## Symbolic links support on Windows

On default Windows installation, unprivileged user can't create symbolic links, so nfs4j may fail to create symbolic 
links too.

You have some options to workaround this issue.

- Run `nfs4j-daemon` as Administrator.
- Tweak the Local Group Policy to allow *Create symbolic links* to the user running `nfs4j-daemon`. (See this [StackOverflow post](https://superuser.com/questions/104845/permission-to-make-symbolic-links-in-windows-7#answer-105381))

## NFSv3 support
### macOS client config
edit `/etc/nfs.conf`, add flowing line

```
nfs.client.mount.options = rw,vers=3,sec=sys
```

### Linux client known issues
- On Ubuntu 22.04 or Debian, list dir is stuck. Just use NFSv4 instead.


## Build from sources
build from source code Java11 and Maven3 are required.

```
mvn clean package
```

### GraalVM

1. install [GraalVM](https://www.graalvm.org/downloads/) `bash <(curl -sL https://get.graalvm.org/jdk) --to <dir> graalvm-ce-java11-22.3.0`
2. set env env variables
   1. JAVA_HOME `export JAVA_HOME="<dir>"`
   2. GRAALVM_HOME `export GRAALVM_HOME="<dir>"`
3. install `native-image` Component `"${GRAALVM_HOME}/bin/gu" install native-image`
4. build image `mvn -Pnative -DskipTests clean package`

