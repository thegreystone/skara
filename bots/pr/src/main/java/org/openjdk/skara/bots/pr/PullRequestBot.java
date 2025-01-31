/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.host.*;
import org.openjdk.skara.json.JSONValue;
import org.openjdk.skara.vcs.Hash;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

class PullRequestBot implements Bot {
    private final HostedRepository remoteRepo;
    private final HostedRepository censusRepo;
    private final String censusRef;
    private final Map<String, List<Pattern>> labelPatterns;
    private final Map<String, String> externalCommands;
    private final Map<String, String> blockingLabels;
    private final ConcurrentMap<Hash, Boolean> currentLabels = new ConcurrentHashMap<>();

    PullRequestBot(HostedRepository repo, HostedRepository censusRepo, String censusRef, Map<String,
            List<Pattern>> labelPatterns, Map<String, String> externalCommands, Map<String, String> blockingLabels) {
        remoteRepo = repo;
        this.censusRepo = censusRepo;
        this.censusRef = censusRef;
        this.labelPatterns = labelPatterns;
        this.externalCommands = externalCommands;
        this.blockingLabels = blockingLabels;
    }

    PullRequestBot(HostedRepository repo, HostedRepository censusRepo, String censusRef) {
        this(repo, censusRepo, censusRef, Map.of(), Map.of(), Map.of());
    }

    private List<WorkItem> getWorkItems(List<PullRequest> pullRequests) {
        var ret = new LinkedList<WorkItem>();

        for (var pr : pullRequests) {
            ret.add(new CheckWorkItem(pr, censusRepo, censusRef, blockingLabels));
            ret.add(new CommandWorkItem(pr, censusRepo, censusRef, externalCommands));
            ret.add(new LabelerWorkItem(pr, labelPatterns, currentLabels));
        }

        return ret;
    }

    HostedRepository repository() {
        return remoteRepo;
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        return getWorkItems(remoteRepo.getPullRequests());
    }

    @Override
    public List<WorkItem> processWebHook(JSONValue body) {
        var webHook = remoteRepo.parseWebHook(body);
        if (webHook.isEmpty()) {
            return new ArrayList<>();
        }

        return getWorkItems(webHook.get().updatedPullRequests());
    }
}
