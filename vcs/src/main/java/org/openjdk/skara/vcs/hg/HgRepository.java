/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.skara.vcs.hg;

import org.openjdk.skara.process.Process;
import org.openjdk.skara.process.Execution;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.tools.*;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.*;
import java.net.URI;

public class HgRepository implements Repository {
    private static final String EXT_PY = "ext.py";
    private final Path dir;
    private final Logger log = Logger.getLogger("org.openjdk.skara.vcs.hg");

    private void copyResource(String name, Path p) throws IOException {
        Files.copy(this.getClass().getResourceAsStream("/" + name), p, StandardCopyOption.REPLACE_EXISTING);
    }

    private java.lang.Process start(String... cmd) throws IOException {
        return start(Arrays.asList(cmd));
    }

    private java.lang.Process start(List<String> cmd) throws IOException {
        log.fine("Executing " + String.join(" ", cmd));
        var pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.environment().put("HGRCPATH", "");
        pb.environment().put("HGPLAIN", "");
        return pb.start();
    }

    private static void stop(java.lang.Process p) throws IOException {
        if (p != null && p.isAlive()) {
            var stream = p.getInputStream();
            var read = 0;
            var buf = new byte[128];
            while (read != -1) {
                read = stream.read(buf);
            }
            try {
                p.waitFor();
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        }
    }

    private Execution capture(List<String> cmd) {
        return capture(cmd.toArray(new String[0]));
    }

    private Execution capture(String... cmd) {
        return capture(dir, cmd);
    }

    private static Execution capture(Path cwd, String... cmd) {
        return Process.capture(cmd)
                      .environ("HGRCPATH", "")
                      .environ("HGPLAIN", "")
                      .workdir(cwd)
                      .execute();
    }

    private static Execution.Result await(Execution e) throws IOException {
        var result = e.await();
        if (result.status() != 0) {
            if (result.exception().isPresent()) {
                throw new IOException("Unexpected exit code\n" + result, result.exception().get());
            } else {
                throw new IOException("Unexpected exit code\n" + result);
            }
        }
        return result;
    }

    private static void await(java.lang.Process p) throws IOException {
        try {
            var res = p.waitFor();
            if (res != 0) {
                throw new IOException("Unexpected exit code: " + res);
            }
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    public HgRepository(Path dir) {
        this.dir = dir.toAbsolutePath();
    }

    @Override
    public List<Branch> branches() throws IOException {
        try (var p = capture("hg", "branches")) {
            return await(p).stdout()
                           .stream()
                           .map(line -> line.split("\\s")[0])
                           .map(Branch::new)
                           .collect(Collectors.toList());
        }
    }

    @Override
    public List<Tag> tags() throws IOException {
        try (var p = capture("hg", "tags")) {
            return await(p).stdout()
                           .stream()
                           .map(line -> line.split("\\s")[0])
                           .map(Tag::new)
                           .collect(Collectors.toList());
        }
    }

    @Override
    public Path root() throws IOException {
        try (var p = capture("hg", "root")) {
            var res = await(p);
            if (res.stdout().size() != 1) {
                throw new IOException("Unexpected output\n" + res);
            }
            return Paths.get(res.stdout().get(0));
        }
    }

    private void checkout(String ref, boolean force) throws IOException {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("hg", "update"));
        if (!force) {
            cmd.add("--check");
        }
        cmd.add(ref);
        try (var p = capture(cmd)) {
            await(p);
        }
    }

    @Override
    public void checkout(Hash h, boolean force) throws IOException {
        checkout(h.hex(), force);
    }

    @Override
    public void checkout(Branch b, boolean force) throws IOException {
        checkout(b.name(), force);
    }

    @Override
    public Optional<Hash> resolve(String ref) throws IOException {
        try (var p = capture("hg", "log", "--rev=" + ref, "--template={node}\n")) {
            var res = p.await();
            if (res.status() == 0 && res.stdout().size() == 1) {
                return Optional.of(new Hash(res.stdout().get(0)));
            }
            return Optional.empty();
        }
    }

    @Override
    public Commits commits() throws IOException {
        return commits(null, -1, false);
    }

    @Override
    public Commits commits(boolean reverse) throws IOException {
        return commits(null, -1, reverse);
    }

    @Override
    public Commits commits(int n) throws IOException {
        return commits(null, n, false);
    }

    @Override
    public Commits commits(int n, boolean reverse) throws IOException {
        return commits(null, n, reverse);
    }

    @Override
    public Commits commits(String range) throws IOException {
        return commits(range, -1, false);
    }

    @Override
    public Commits commits(String range, int n) throws IOException {
        return commits(range, n, false);
    }

    @Override
    public Commits commits(String range, boolean reverse) throws IOException {
        return commits(range, -1, reverse);
    }

    @Override
    public Commits commits(String range, int n,  boolean reverse) throws IOException {
        var ext = Files.createTempFile("ext", ".py");
        copyResource(EXT_PY, ext);
        return new HgCommits(dir, range, ext, reverse, n);
    }

    @Override
    public Optional<Commit> lookup(Hash h) throws IOException {
        var commits = commits(h.hex()).asList();
        if (commits.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(commits.get(0));
    }

    @Override
    public Optional<Commit> lookup(Branch b) throws IOException {
        var hash = resolve(b.name()).orElseThrow(() -> new IOException("Branch " + b.name() + " not found"));
        return lookup(hash);
    }

    @Override
    public Optional<Commit> lookup(Tag t) throws IOException {
        var hash = resolve(t.name()).orElseThrow(() -> new IOException("Tag " + t.name() + " not found"));
        return lookup(hash);
    }

    @Override
    public List<CommitMetadata> commitMetadata() throws IOException {
        var ext = Files.createTempFile("ext", ".py");
        copyResource(EXT_PY, ext);

        var p = start("hg", "--config", "extensions.dump=" + ext.toAbsolutePath().toString(), "metadata");
        var reader = new UnixStreamReader(p.getInputStream());
        var result = new ArrayList<CommitMetadata>();

        var line = reader.readLine();
        while (line != null) {
            result.add(HgCommitMetadata.read(reader));
            line = reader.readLine();
        }

        await(p);
        return result;
    }

    @Override
    public boolean isEmpty() throws IOException {
        var numBranches = branches().size();
        var numTags = tags().size();

        if (numBranches > 0 || numTags > 1) {
            return false;
        }

        var tip = resolve("tip");
        return tip.isEmpty() || tip.get().hex().equals("0".repeat(40));
    }

    @Override
    public boolean isHealthy() throws IOException {
        var root = root().toString();
        return !(Files.exists(Path.of(root, ".hg", "wlock")) ||
                 Files.exists(Path.of(root, ".hg", "store", "lock")));
    }

    @Override
    public void clean() throws IOException {
        try (var p = capture("hg", "merge", "--abort")) {
            p.await();
        }

        try (var p = capture("hg", "recover")) {
            p.await();
        }

        try (var p = capture("hg", "status", "--ignored", "--no-status")) {
            var root = root().toString();
            for (var filename : await(p).stdout()) {
                Files.delete(Path.of(root, filename));
            }
        }

        try (var p = capture("hg", "status", "--unknown", "--no-status")) {
            var root = root().toString();
            for (var filename : await(p).stdout()) {
                Files.delete(Path.of(root, filename));
            }
        }

        try (var p = capture("hg", "revert", "--no-backup", "--all")) {
            await(p);
        }
    }

    @Override
    public Repository reinitialize() throws IOException {
        Files.walk(dir)
             .map(Path::toFile)
             .sorted(Comparator.reverseOrder())
             .forEach(File::delete);

        return init();
    }

    @Override
    public Hash fetch(URI uri, String refspec) throws IOException {
        var oldHeads = new HashSet<Hash>(heads());

        try (var p = capture("hg", "pull", "--rev=" + refspec, uri.toString())) {
            await(p);
        }

        var newHeads = new HashSet<Hash>(heads());
        newHeads.removeAll(oldHeads);

        if (newHeads.size() > 1) {
            throw new IllegalStateException("fetching multiple heads is not supported");
        } else if (newHeads.size() == 0) {
            // no new head was fetched, return current head
            return head();
        }
        return newHeads.iterator().next();
    }

    @Override
    public Repository init() throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }

        try (var p = capture("hg", "init")) {
            await(p);
            return this;
        }
    }

    @Override
    public void pushAll(URI uri) throws IOException {
        try (var p = capture("hg", "push", "--new-branch", uri.toString())) {
            await(p);
        }
    }

    @Override
    public void push(Hash hash, URI uri, String ref, boolean force) throws IOException {
        var cmd = new ArrayList<>(List.of("hg", "push", "--rev=" + hash.hex()));
        if (force) {
            cmd.add("--force");
        }
        cmd.add(uri.toString() + "#" + ref);
        try (var p = capture(cmd)) {
            await(p);
        }
    }

    @Override
    public void push(Branch branch, String remote, boolean setUpstream) throws IOException {
        // ignore setUpstream, no such concept in Mercurial
        try (var p = capture("hg", "push", "--branch", branch.name(), remote)) {
            await(p);
        }
    }

    @Override
    public boolean isClean() throws IOException {
        try (var p = capture("hg", "status")) {
            var output = await(p);
            return output.stdout().size() == 0;
        }
    }

    @Override
    public boolean exists() throws IOException {
        try {
            root();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void export(String revset, Path to) throws IOException {
        var cmd = List.of("hg", "export", "--git", "--rev", revset);
        log.fine("Executing " + String.join(" ", cmd));
        var pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.redirectOutput(to.toFile());
        pb.environment().put("HGRCPATH", "");
        pb.environment().put("HGPLAIN", "");
        var p = pb.start();
        try {
            await(p);
        } catch (Throwable t) {
            if (p.isAlive()) {
                try {
                    p.waitFor();
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }

            throw new IOException(t);
        }
    }

    @Override
    public void squash(Hash h) throws IOException {
        var revset = ".:" + h.hex() + " and not .";
        var patch = Files.createTempFile("squash", ".patch");
        export(revset, patch);

        try (var p = capture("hg", "--config", "extensions.mq=", "strip", "--rev", revset)) {
            await(p);
        }

        try (var p = capture("hg", "import", "--no-commit", patch.toString())) {
            await(p);
        }
    }


    @Override
    public Hash commit(String message, String authorName, String authorEmail)  throws IOException {
        return commit(message, authorName, authorEmail, null);
    }

    @Override
    public Hash commit(String message, String authorName, String authorEmail, Instant authorDate)  throws IOException {
        var user = authorEmail == null ? authorName : authorName + " <" + authorEmail + ">";
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("hg", "commit", "--message=" + message, "--user=" + user));
        if (authorDate != null) {
            var date = ZonedDateTime.ofInstant(authorDate, ZoneOffset.UTC);
            cmd.add("--date=" + date.toEpochSecond() + " 0");
        }
        try (var p = capture(cmd)) {
            await(p);
        }
        return resolve("tip").orElseThrow(() -> new IOException("Could not resolve 'tip'"));
    }

    @Override
    public Hash commit(String message,
                       String authorName,
                       String authorEmail,
                       String committerName,
                       String committerEmail) throws IOException {
        return commit(message, authorName, authorEmail, null, committerName, committerEmail, null);
    }

    @Override
    public Hash commit(String message,
                       String authorName,
                       String authorEmail,
                       Instant authorDate,
                       String committerName,
                       String committerEmail,
                       Instant committerDate) throws IOException {
        if (!Objects.equals(authorName, committerName) ||
            !Objects.equals(authorEmail, committerEmail) ||
            !Objects.equals(authorDate, committerDate)) {
            throw new IllegalArgumentException("hg does not support different author and committer data");
        }

        return commit(message, authorName, authorEmail, authorDate);
    }

    @Override
    public Hash amend(String message, String authorName, String authorEmail) throws IOException {
        throw new RuntimeException("Not implemented yet");
    }

    @Override
    public Hash amend(String message, String authorName, String authorEmail, String committerName, String committerEmail) throws IOException {
        throw new RuntimeException("Not implemented yet");
    }

    @Override
    public Tag tag(Hash hash, String name, String message, String authorName, String authorEmail) throws IOException {
        var user = authorName + " <" + authorEmail + ">";
        try (var p = capture("hg", "tag",
                             "--message", message,
                             "--user", user,
                             "--rev", hash.hex(),
                             name)) {
            await(p);
        }

        return new Tag(name);
    }

    @Override
    public Branch branch(Hash hash, String name) throws IOException {
        // Model a lightweight branch with a bookmark. Not ideal but the
        // closest to git branches.
        try (var p = capture("hg", "bookmark", "--rev", hash.hex(), name)) {
            await(p);
        }

        return new Branch(name);
    }

    @Override
    public Hash mergeBase(Hash first, Hash second) throws IOException {
        var revset = "ancestor(" + first.hex() + ", " + second.hex() + ")";
        try (var p = capture("hg", "log", "--rev=" + revset, "--template={node}\n")) {
            var res = await(p);
            if (res.stdout().size() != 1) {
                throw new IOException("Unexpected output\n" + res);
            }
            return new Hash(res.stdout().get(0));
        }
    }

    @Override
    public boolean isAncestor(Hash ancestor, Hash descendant) throws IOException {
        throw new RuntimeException("Not implemented yet");
    }

    @Override
    public void rebase(Hash hash, String committerName, String committerEmail) throws IOException {
        var current = currentBranch().name();
        try (var p = capture("hg", "--config", "extensions.rebase=",
                             "rebase", "--dest", hash.hex(), "--base", current)) {
            await(p);
        }
    }

    @Override
    public Branch currentBranch() throws IOException {
        try (var p = capture("hg", "branch")) {
            var res = await(p);
            if (res.stdout().size() != 1) {
                throw new IllegalStateException("No current branch\n" + res);
            }
            return new Branch(res.stdout().get(0));
        }
    }

    @Override
    public Branch defaultBranch() throws IOException {
        return new Branch("default");
    }

    @Override
    public Optional<Tag> defaultTag() throws IOException {
        return Optional.of(new Tag("tip"));
    }


    @Override
    public Optional<byte[]> show(Path path, Hash hash) throws IOException {
        var output = Files.createTempFile("hg-cat-rev-" + hash.abbreviate(), ".bin");
        try (var p = capture("hg", "cat", "--output=" + output, "--rev=" + hash.hex(), path.toString())) {
            var res = p.await();
            if (res.status() == 0 && Files.exists(output)) {
                var bytes = Files.readAllBytes(output);
                Files.delete(output);
                return Optional.of(bytes);
            }

            if (Files.exists(output)) {
                Files.delete(output);
            }
            return Optional.empty();
        }
    }

    @Override
    public Diff diff(Hash from) throws IOException {
        return diff(from, null);
    }

    @Override
    public Diff diff(Hash from, Hash to) throws IOException {
        var ext = Files.createTempFile("ext", ".py");
        copyResource(EXT_PY, ext);

        var cmd = new ArrayList<>(List.of("hg", "--config", "extensions.diff-git-raw=" + ext.toAbsolutePath(),
                                                "diff-git-raw", from.hex()));
        if (to != null) {
            cmd.add(to.hex());
        }

        var p = start(cmd);
        try {
            var patches = UnifiedDiffParser.parseGitRaw(p.getInputStream());
            await(p);
            return new Diff(from, to, patches);
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    @Override
    public Optional<String> username() throws IOException {
        var lines = config("ui.username");
        return lines.size() == 1 ? Optional.of(lines.get(0)) : Optional.empty();
    }

    @Override
    public Hash head() throws IOException {
        return resolve(".").orElseThrow(() -> new IOException(". not available"));
    }

    private List<Hash> heads() throws IOException {
        var heads = new ArrayList<Hash>();
        try (var p = capture("hg", "heads", "--template={node}\n")) {
            var res = p.await();
            if (res.status() == 0) {
                for (var hash : res.stdout()) {
                    heads.add(new Hash(hash));
                }
            }
        }
        return heads;
    }

    @Override
    public List<String> config(String key) throws IOException {
        // Do not use HgRepository.capture() here, want to run *with*
        // hg configuration.
        try (var p = Process.capture("hg", "showconfig", key)
                            .workdir(dir)
                            .execute()) {
            var res = p.await();
            if (res.status() == 1) {
                return List.of();
            }
            return res.stdout();
        }
    }

    public static Optional<Repository> get(Path p) throws IOException {
        var r = new HgRepository(p);
        return r.exists() ? Optional.of(new HgRepository(r.root())) : Optional.empty();
    }

    @Override
    public Repository copyTo(Path destination) throws IOException {
        var from = root().toAbsolutePath().toString();
        var to = destination.toAbsolutePath().toString();
        try (var p = capture("hg", "clone", from, to)) {
            await(p);
        }

        return new HgRepository(destination.toAbsolutePath());
    }

    @Override
    public void merge(Hash h) throws IOException {
        merge(h, null);
    }

    @Override
    public void merge(Hash h, String stragegy) throws IOException {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("hg", "merge", "--rev=" + h.hex()));
        if (stragegy != null) {
            cmd.add("--tool=" + stragegy);
        }
        try (var p = capture(cmd)) {
            await(p);
        }
    }

    @Override
    public void addRemote(String name, String path) throws IOException {
        setPaths(name, path, path);
    }

    @Override
    public void setPaths(String remote, String pullPath, String pushPath) throws IOException {
        var hgrc = Path.of(root().toString(), ".hg", "hgrc");
        if (!Files.exists(hgrc)) {
            Files.createFile(hgrc);
        }

        var lines = Files.readAllLines(hgrc);
        var newLines = new ArrayList<String>();

        var isInPathsSection = false;
        var hasPathsSection = false;
        for (var line : lines) {
            var isSectionHeader = line.startsWith("[") && line.endsWith("]");
            if (isSectionHeader && !isInPathsSection) {
                isInPathsSection = line.equals("[paths]");
                if (isInPathsSection) {
                    newLines.add(line);
                    newLines.add(remote + " = " + (pullPath == null ? "" : pullPath));
                    newLines.add(remote + "-push = " + (pushPath == null ? "" : pushPath));
                    hasPathsSection = true;
                    continue;
                }
            }

            if (isInPathsSection && line.startsWith(remote)) {
                if (line.startsWith(remote + "-push")) {
                    // skip
                } else if (line.startsWith(remote + ":pushurl")) {
                    // skip
                } else if (line.startsWith(remote + " ") || line.startsWith(remote + "=")) {
                    // skip
                } else {
                    newLines.add(line);
                }
            } else {
                newLines.add(line);
            }
        }

        Files.write(hgrc, newLines, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        if (!hasPathsSection) {
            var section = List.of("[paths]",
                                  remote + " = " + (pullPath == null ? "" : pullPath),
                                  remote + "-push = " + (pushPath == null ? "" : pushPath));
            Files.write(hgrc, section, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        }
    }

    @Override
    public String pullPath(String remote) throws IOException {
        var lines = config("paths." + remote);
        if (lines.size() != 1) {
            throw new IOException("Pull path not found for remote: " + remote);
        }
        return lines.get(0);
    }

    @Override
    public String pushPath(String remote) throws IOException {
        var lines = config("paths." + remote + "-push");
        if (lines.size() != 1) {
            lines = config("paths." + remote + "@push");
        }
        if (lines.size() != 1) {
            return pullPath(remote);
        }
        return lines.get(0);
    }

    @Override
    public boolean isValidRevisionRange(String expression) throws IOException {
        try (var p = capture("hg", "log", "--template", " ", "--rev", expression)) {
            return p.await().status() == 0;
        }
    }

    private void setPermissions(Patch.Info target) throws IOException {
        if (target.path().isPresent() && target.type().isPresent()) {
            var perms = target.type().get().permissions();
            if (perms.isPresent()) {
                Files.setPosixFilePermissions(target.path().get(), perms.get());
            }
        }
    }

    @Override
    public void apply(Diff diff, boolean force) throws IOException {
        var patchFile = Files.createTempFile("import", ".patch");
        diff.toFile(patchFile);
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("hg", "import", "--no-commit"));
        if (force) {
            cmd.add("--force");
        }
        cmd.add(patchFile.toAbsolutePath().toString());
        try (var p = capture(cmd)) {
            await(p);
        }
        //Files.delete(patchFile);
    }

    @Override
    public void copy(Path from, Path to) throws IOException {
        try (var p = capture("hg", "copy", from.toString(), to.toString())) {
            await(p);
        }
    }

    @Override
    public void move(Path from, Path to) throws IOException {
        try (var p = capture("hg", "move", from.toString(), to.toString())) {
            await(p);
        }
    }

    @Override
    public void remove(Path... paths) throws IOException {
        var cmd = new ArrayList<>(List.of("hg", "rm"));
        for (var p : paths) {
            cmd.add(p.toString());
        }
        try (var p = capture(cmd)) {
            await(p);
        }
    }

    @Override
    public void add(Path... paths) throws IOException {
        var cmd = new ArrayList<>(List.of("hg", "add"));
        for (var p : paths) {
            cmd.add(p.toString());
        }
        try (var p = capture(cmd)) {
            await(p);
        }
    }

    @Override
    public Optional<String> upstreamFor(Branch b) throws IOException {
        // Mercurial doesn't have the concept of remotes like git,
        // a local branch must have the same name (if present) on the remote
        return Optional.of(b.name());
    }

    public static Repository clone(URI from, Path to) throws IOException {
        try (var p = capture(Path.of("").toAbsolutePath(), "hg", "clone", from.toString(), to.toString())) {
            await(p);
        }
        return new HgRepository(to);
    }

    @Override
    public void pull() throws IOException {
        pull("default", "default");
    }

    @Override
    public void pull(String remote) throws IOException {
        pull(remote, "default");
    }

    @Override
    public void pull(String remote, String refspec) throws IOException {
        try (var p = capture("hg", "pull", "--update", "--branch", refspec, remote)) {
            await(p);
        }
    }
}
