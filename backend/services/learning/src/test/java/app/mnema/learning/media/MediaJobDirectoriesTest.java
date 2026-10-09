package app.mnema.learning.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MediaJobDirectoriesTest {
    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");

    @TempDir Path root;

    private Path job(String name, Duration age) throws IOException {
        Path job = Files.createDirectories(root.resolve(name).resolve("output"));
        Files.writeString(job.resolve("variant"), "bytes");
        Files.setLastModifiedTime(job.getParent(), FileTime.from(NOW.minus(age)));
        return job.getParent();
    }

    @Test
    void sweepRemovesStaleUnownedEntriesAndKeepsTheRest() throws IOException {
        Path stale = job("media-1", Duration.ofHours(3));
        Path fresh = job("media-2", Duration.ofMinutes(10));
        Path running = job("media-3", Duration.ofHours(5));
        Path junk = job("not-a-job", Duration.ofHours(9));          // anything a misbehaving writer left behind goes too
        Path freshJunk = job("recent-junk", Duration.ofMinutes(5));
        Path lostFound = job("lost+found", Duration.ofDays(30));    // belongs to the filesystem
        Path outside = Files.createTempDirectory("mnema-outside");
        Files.writeString(Files.createFile(outside.resolve("keep")), "x");
        Path link = Files.createSymbolicLink(root.resolve("media-4"), outside);
        FileTime old = FileTime.from(NOW.minus(Duration.ofDays(2)));
        Files.getFileAttributeView(link, java.nio.file.attribute.BasicFileAttributeView.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS).setTimes(old, null, null);   // the link's own time, not its target's
        Path privateCopies = Files.createDirectories(root.resolve(".private"));
        Path oldCopy = job(".private/v-old", Duration.ofHours(4));
        Path newCopy = job(".private/v-new", Duration.ofMinutes(1));
        Files.setLastModifiedTime(privateCopies, FileTime.from(NOW.minus(Duration.ofDays(30))));

        int removed = MediaJobDirectories.sweepStale(root, Duration.ofHours(2), Set.of(running), NOW);

        try (var left = Files.list(root)) {
            assertThat(left.map(path -> path.getFileName().toString()).sorted().toList())
                    .containsExactly(".private", "lost+found", "media-2", "media-3", "recent-junk");
        }
        assertThat(removed).isEqualTo(4);
        assertThat(stale).doesNotExist();
        assertThat(junk).doesNotExist();
        assertThat(oldCopy).doesNotExist();
        assertThat(fresh).exists();
        assertThat(running).exists();
        assertThat(freshJunk).exists();
        assertThat(lostFound).exists();
        assertThat(privateCopies).exists();                          // the scratch directory itself stays
        assertThat(newCopy).exists();
        assertThat(link).doesNotExist();                              // unlinked ...
        assertThat(outside.resolve("keep")).exists();                 // ... never followed
    }

    @Test
    void startupSweepRemovesLeftoverJobsAndCopiesButNoOtherEntry() throws IOException {
        Path job = job("media-1", Duration.ZERO);
        Path copy = job(".private/v-1", Duration.ZERO);
        Path other = job("data", Duration.ofDays(30));
        assertThat(MediaJobDirectories.sweepAll(root)).isEqualTo(2);
        assertThat(job).doesNotExist();
        assertThat(copy).doesNotExist();
        assertThat(other).exists();
        assertThat(root.resolve(".private")).exists();
    }

    @Test
    void sweepOfAMissingRootIsHarmless() {
        assertThat(MediaJobDirectories.sweepStale(root.resolve("absent"), Duration.ofHours(2), Set.of(), NOW)).isZero();
        assertThat(MediaJobDirectories.sweepAll(root.resolve("absent"))).isZero();
    }

    @Test
    void deleteRemovesTheWholeTreeButNeverFollowsALinkOutOfIt() throws IOException {
        Path job = job("media-9", Duration.ZERO);
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.writeString(outside.resolve("precious"), "keep");
        Files.createSymbolicLink(job.resolve("output").resolve("escape"), outside);   // a link to a directory
        Files.createSymbolicLink(job.resolve("file-escape"), outside.resolve("precious"));

        MediaJobDirectories.delete(job);

        assertThat(job).doesNotExist();
        assertThat(outside.resolve("precious")).hasContent("keep");
    }

    @Test
    void aJobDirectoryThatIsItselfALinkIsUnlinkedNotEntered() throws IOException {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.writeString(outside.resolve("precious"), "keep");
        Path link = Files.createSymbolicLink(root.resolve("media-link"), outside);
        MediaJobDirectories.delete(link);
        assertThat(link).doesNotExist();
        assertThat(outside.resolve("precious")).hasContent("keep");
    }

    @Test
    void deleteRemovesWhatItCanWhenAnEntryCannotBeEntered() throws IOException {
        Path job = job("media-5", Duration.ZERO);
        Path closed = Files.createDirectory(job.resolve("closed"));
        Files.writeString(closed.resolve("x"), "x");
        Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("---------"));
        try {
            MediaJobDirectories.delete(job);                          // must not throw
            assertThat(job.resolve("output")).doesNotExist();
        } finally {
            Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void aLinkOrFileInPlaceOfThePrivateScratchDirectoryIsRefused() throws IOException {
        Path elsewhere = Files.createDirectory(root.resolve("elsewhere"));
        Files.createSymbolicLink(root.resolve(".private"), elsewhere);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> MediaJobDirectories.newPrivateDirectory(root))
                .isInstanceOf(IOException.class);
        assertThat(elsewhere).isEmptyDirectory();
    }

    @Test
    void privateDirectoriesAreOwnerOnly() throws IOException {
        Path directory = MediaJobDirectories.newPrivateDirectory(root);
        assertThat(directory.getParent()).isEqualTo(root.resolve(".private"));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(directory))).isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve(".private")))).isEqualTo("rwx------");
    }
}
