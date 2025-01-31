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
package org.openjdk.skara.webrev;

import org.openjdk.skara.vcs.TextualPatch;

import java.io.*;
import java.nio.file.*;

class RemovedPatchView implements View {
    private final Path out;
    private final Path file;
    private final TextualPatch patch;

    public RemovedPatchView(Path out, Path file, TextualPatch patch) {
        this.out = out;
        this.file = file;
        this.patch = patch;
    }

    public void render(Writer w) throws IOException {
        var patchFile = out.resolve(file.toString() + ".patch");
        Files.createDirectories(patchFile.getParent());

        try (var fw = Files.newBufferedWriter(patchFile)) {
            fw.write("diff a/");
            fw.write(patch.source().path().get().toString());
            fw.write(" b/");
            fw.write(patch.source().path().get().toString());
            fw.write("\n");
            fw.write("--- a/");
            fw.write(patch.source().path().get().toString());
            fw.write("\n");
            fw.write("+++ /dev/null");
            fw.write("\n");

            assert patch.hunks().size() == 1;

            var hunk = patch.hunks().get(0);

            assert hunk.target().range().start() == 0;
            assert hunk.target().range().count() == 0;
            assert hunk.target().lines().size() == 0;

            fw.write("@@ -");
            fw.write(String.valueOf(hunk.source().range().start()));
            fw.write(",");
            fw.write(String.valueOf(hunk.source().range().count()));
            fw.write(" +");
            fw.write(String.valueOf(hunk.target().range().start()));
            fw.write(",");
            fw.write(String.valueOf(hunk.target().range().count()));
            fw.write(" @@\n");

            for (var line : hunk.source().lines()) {
                fw.write("-");
                fw.write(line);
                fw.write("\n");
            }
        }

        w.write("<a href=\"");
        w.write(Webrev.relativeToIndex(out, patchFile));
        w.write("\">Patch</a>\n");
    }
}

