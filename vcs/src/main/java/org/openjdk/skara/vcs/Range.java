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
package org.openjdk.skara.vcs;

import java.util.Objects;

public class Range {
    private final int start;
    private final int count;

    public Range(int start, int count) {
        this.start = start;
        this.count = count;
    }

    public static Range fromString(String s) {
        var separatorIndex = s.indexOf(",");

        if (separatorIndex == -1) {
            var start = Integer.parseInt(s);
            return new Range(start, 1);
        }

        var start = Integer.parseInt(s.substring(0, separatorIndex));

        // Need to work arond a bug in git where git sometimes print -1
        // as an unsigned int for the count part of the range
        var countString = s.substring(separatorIndex + 1, s.length());
        var count =
            countString.equals("18446744073709551615") ?  0 : Integer.parseInt(countString);

        return new Range(start, count);
    }

    public int start() {
        return this.start;
    }

    public int count() {
        return this.count;
    }

    public int end() {
        return start + count;
    }

    @Override
    public String toString() {
        return Integer.toString(start) + "," + Integer.toString(count);
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, count);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Range)) {
            return false;
        }

        var other = (Range) o;
        return start == other.start && count == other.count;
    }
}
