/*
 *
 *  * Copyright 2025 Victor Denisov
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package org.transflux.yaml.source

import org.transflux.core.exception.TransfluxValidationException
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.FileTime
import java.time.Instant

class FileSystemDefinitionSourceSpec extends Specification {

    @TempDir
    Path dir

    Path root

    def setup() {
        root = Files.createDirectory(dir.resolve('root'))
        write(root.resolve('components/shared.yml'), 'shared')
        write(dir.resolve('outside.yml'), 'outside')
        write(dir.resolve('elsewhere/outside.yml'), 'outside')
    }

    def 'opens a file under the root, reporting its modification time'() {
        given:
        def modified = Instant.parse('2026-01-01T00:00:00Z')
        Files.setLastModifiedTime(root.resolve('components/shared.yml'), FileTime.from(modified))

        when:
        def resource = new FileSystemDefinitionSource(root).open('components/shared.yml')

        then:
        resource.get().identifier() == 'components/shared.yml'
        resource.get().withCloseable { it.bytes().text } == 'shared'
        resource.get().location() == root.resolve('components/shared.yml').toRealPath().toString()
        resource.get().lastModified() == modified
        resource.get().etag() == null
    }

    def "answers to 'file:' unless given other prefixes, keeping its root and policy"() {
        given:
        def source = new FileSystemDefinitionSource(root, SymlinkPolicy.REJECT)

        expect:
        source.prefixes() == ['file:'] as Set
        source.withPrefixes('home:', 'file:').prefixes() == ['home:', 'file:'] as Set
        source.withPrefixes().prefixes().empty
        read(source.withPrefixes('home:'), 'components/shared.yml') == 'shared'
    }

    def "a path naming no regular file is empty under every policy: '#identifier', #policy"() {
        expect:
        new FileSystemDefinitionSource(root, policy).open(identifier).empty

        where:
        [identifier, policy] << [
            ['components/missing.yml', 'components', 'nowhere/missing.yml', 'components/shared.yml/nested.yml'],
            SymlinkPolicy.values()
        ].combinations()
    }

    def "a path with a '..' segment is refused, not treated as missing, even one landing back under the root"() {
        when:
        new FileSystemDefinitionSource(root).open(identifier)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains(identifier)

        where:
        identifier << [
            '../outside.yml',
            'components/../../outside.yml',
            'components/../shared.yml'
        ]
    }

    def 'an absolute path is refused'() {
        when:
        new FileSystemDefinitionSource(root).open(dir.resolve('outside.yml').toString())

        then:
        thrown(TransfluxValidationException)
    }

    @Requires({ System.getProperty('os.name').startsWith('Windows') })
    def 'a drive-relative or root-relative Windows path is refused'() {
        when:
        new FileSystemDefinitionSource(root).open(identifier)

        then:
        thrown(TransfluxValidationException)

        where:
        identifier << [
            'C:outside.yml',
            '\\outside.yml'
        ]
    }

    def 'rejects a blank identifier and null arguments'() {
        when:
        new FileSystemDefinitionSource(root).open(' ')

        then:
        thrown(TransfluxValidationException)

        when:
        new FileSystemDefinitionSource(null)

        then:
        thrown(TransfluxValidationException)

        when:
        new FileSystemDefinitionSource(root, null)

        then:
        thrown(TransfluxValidationException)
    }

    @Requires({ symlinksSupported() })
    def 'a link staying under the root is followed, except under REJECT'() {
        given:
        Files.createSymbolicLink(root.resolve('alias.yml'), root.resolve('components/shared.yml'))
        Files.createSymbolicLink(root.resolve('linked'), root.resolve('components'))

        expect:
        read(new FileSystemDefinitionSource(root), 'alias.yml') == 'shared'
        read(new FileSystemDefinitionSource(root), 'linked/shared.yml') == 'shared'
        read(new FileSystemDefinitionSource(root, SymlinkPolicy.FOLLOW), 'linked/shared.yml') == 'shared'

        when:
        new FileSystemDefinitionSource(root, SymlinkPolicy.REJECT).open(identifier)

        then:
        thrown(TransfluxValidationException)

        where:
        identifier << [
            'alias.yml',
            'linked/shared.yml'
        ]
    }

    @Requires({ symlinksSupported() })
    def 'a link leading out of the root is refused unless the policy is FOLLOW'() {
        given:
        Files.createSymbolicLink(root.resolve('escape.yml'), dir.resolve('outside.yml'))
        Files.createSymbolicLink(root.resolve('escape'), dir.resolve('elsewhere'))

        expect:
        read(new FileSystemDefinitionSource(root, SymlinkPolicy.FOLLOW), identifier) == 'outside'

        when:
        new FileSystemDefinitionSource(root, policy).open(identifier)

        then:
        thrown(TransfluxValidationException)

        where:
        identifier           | policy
        'escape.yml'         | SymlinkPolicy.WITHIN_ROOT
        'escape/outside.yml' | SymlinkPolicy.WITHIN_ROOT
        'escape.yml'         | SymlinkPolicy.REJECT
        'escape/outside.yml' | SymlinkPolicy.REJECT
    }

    @Requires({ symlinksSupported() })
    def 'the root itself may be a link, under every policy'() {
        given:
        def linkedRoot = Files.createSymbolicLink(dir.resolve('root-link'), root)

        expect:
        read(new FileSystemDefinitionSource(linkedRoot, policy), 'components/shared.yml') == 'shared'

        where:
        policy << SymlinkPolicy.values()
    }

    @Requires({ junctionsSupported() })
    def 'a Windows junction is refused under REJECT, and under WITHIN_ROOT when it leads out of the root'() {
        given:
        junction(root.resolve('inside'), root.resolve('components'))
        junction(root.resolve('escape'), dir.resolve('elsewhere'))

        expect:
        read(new FileSystemDefinitionSource(root), 'inside/shared.yml') == 'shared'
        read(new FileSystemDefinitionSource(root, SymlinkPolicy.FOLLOW), 'escape/outside.yml') == 'outside'

        when:
        new FileSystemDefinitionSource(root, policy).open(identifier)

        then:
        thrown(TransfluxValidationException)

        where:
        identifier           | policy
        'inside/shared.yml'  | SymlinkPolicy.REJECT
        'escape/outside.yml' | SymlinkPolicy.REJECT
        'escape/outside.yml' | SymlinkPolicy.WITHIN_ROOT
    }

    @Requires({ System.getProperty('os.name').startsWith('Windows') })
    def 'a file the source may not examine is a failure, not a miss, under #policy'() {
        given: 'a deny entry on the file and its directory, so its attributes cannot be read'
        def file = root.resolve('components/shared.yml')
        def restore = []
        restore << denyRead(file)
        restore << denyRead(file.parent)

        when:
        new FileSystemDefinitionSource(root, policy).open('components/shared.yml')

        then: 'a composite would otherwise serve a later source in its place'
        thrown(UncheckedIOException)

        cleanup:
        restore.reverse().each { it.run() }

        where:
        policy << SymlinkPolicy.values()
    }

    @Requires({ !System.getProperty('os.name').startsWith('Windows') && System.getProperty('user.name') != 'root' })
    def 'a file under a directory the source may not search is a failure, not a miss, under #policy'() {
        given:
        def directory = root.resolve('components')
        def permissions = Files.getPosixFilePermissions(directory)
        Files.setPosixFilePermissions(directory, [] as Set)

        when:
        new FileSystemDefinitionSource(root, policy).open('components/shared.yml')

        then:
        thrown(UncheckedIOException)

        cleanup:
        Files.setPosixFilePermissions(directory, permissions)

        where:
        policy << SymlinkPolicy.values()
    }

    /** Creating a link needs a privilege Windows grants only in developer mode or to administrators. */
    static boolean symlinksSupported() {
        def probeDir = Files.createTempDirectory('transflux-symlink-probe')
        try {
            Files.createSymbolicLink(probeDir.resolve('link'), probeDir)
            return true
        } catch (FileSystemException | UnsupportedOperationException ignored) {
            return false
        } finally {
            Files.deleteIfExists(probeDir.resolve('link'))
            Files.delete(probeDir)
        }
    }

    /** Junctions exist on Windows only; skip rather than fail wherever one cannot be made. */
    static boolean junctionsSupported() {
        if (!System.getProperty('os.name').startsWith('Windows')) {
            return false
        }
        def probeDir = Files.createTempDirectory('transflux-junction-probe')
        try {
            junction(probeDir.resolve('link'), probeDir)
            return true
        } catch (IOException ignored) {
            return false
        } finally {
            Files.deleteIfExists(probeDir.resolve('link'))
            Files.delete(probeDir)
        }
    }

    private static void junction(Path link, Path target) {
        def process = new ProcessBuilder('cmd', '/c', 'mklink', '/J', link.toString(), target.toString())
            .redirectErrorStream(true).start()
        process.inputStream.text
        if (process.waitFor() != 0 || !Files.exists(link)) {
            throw new IOException("mklink /J failed for $link")
        }
    }

    /**
     * Denies the current user reading a path's attributes and data, as an inherited deny entry on a
     * source root would.
     *
     * @param path the file or directory to deny
     *
     * @return what restores the path's previous entries
     */
    private static Runnable denyRead(Path path) {
        def view = Files.getFileAttributeView(path, AclFileAttributeView)
        def original = view.acl
        // The spec created the path, so its owner is the account running it.
        def me = Files.getOwner(path)
        def deny = AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(me)
            .setPermissions(EnumSet.of(AclEntryPermission.READ_ATTRIBUTES, AclEntryPermission.READ_DATA,
                                       AclEntryPermission.READ_NAMED_ATTRS, AclEntryPermission.EXECUTE))
            .build()
        view.acl = [deny] + original
        return { view.acl = original } as Runnable
    }

    private static String read(DefinitionSource source, String identifier) {
        return source.open(identifier).get().withCloseable { it.bytes().text }
    }

    private static void write(Path file, String text) {
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }
}
