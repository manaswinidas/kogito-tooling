/*
 * Copyright 2015 JBoss, by Red Hat, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.uberfire.ext.metadata.io;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.uberfire.ext.metadata.model.KObject;
import org.uberfire.ext.metadata.search.ClusterSegment;
import org.uberfire.ext.metadata.search.IOSearchService;
import org.uberfire.ext.metadata.search.SearchIndex;
import org.uberfire.io.IOService;
import org.uberfire.java.nio.base.FileSystemId;
import org.uberfire.java.nio.base.SegmentedPath;
import org.uberfire.java.nio.file.Path;

import static org.kie.soup.commons.validation.PortablePreconditions.checkNotNull;
import static org.uberfire.ext.metadata.backend.lucene.index.directory.DirectoryFactory.*;

/**
 *
 */
public class IOSearchServiceImpl implements IOSearchService {

    private final SearchIndex searchIndex;
    private final IOService ioService;

    public IOSearchServiceImpl(final SearchIndex searchIndex,
                               final IOService ioService) {
        this.searchIndex = checkNotNull("searchIndex",
                                        searchIndex);
        this.ioService = checkNotNull("ioService",
                                      ioService);
    }

    @Override
    public List<Path> searchByAttrs(final Map<String, ?> attrs,
                                    final Filter filter,
                                    final Path... roots) {
        final List<KObject> kObjects = searchIndex.searchByAttrs(attrs,
                                                                 filter,
                                                                 buildClusterSegments(roots));
        return new ArrayList<Path>() {{
            for (KObject kObject : kObjects) {
                add(ioService.get(URI.create(kObject.getKey())));
            }
        }};
    }

    @Override
    public List<Path> fullTextSearch(final String _term,
                                     final Filter filter,
                                     final Path... roots) {
        final String term = checkNotNull("term",
                                         _term).trim();
        if (term.isEmpty()) {
            return Collections.emptyList();
        }
        final List<KObject> kObjects = searchIndex.fullTextSearch(term,
                                                                  filter,
                                                                  buildClusterSegments(roots));
        return new ArrayList<Path>() {{
            for (KObject kObject : kObjects) {
                add(ioService.get(URI.create(kObject.getKey())));
            }
        }};
    }

    @Override
    public int searchByAttrsHits(final Map<String, ?> attrs,
                                 final Path... roots) {
        return searchIndex.searchByAttrsHits(attrs,
                                             buildClusterSegments(roots));
    }

    @Override
    public int fullTextSearchHits(final String term,
                                  final Path... roots) {
        return searchIndex.fullTextSearchHits(term,
                                              buildClusterSegments(roots));
    }

    private ClusterSegment[] buildClusterSegments(final Path[] roots) {
        if (roots == null || roots.length == 0) {
            return new ClusterSegment[0];
        }
        final ClusterSegment[] clusterSegments = new ClusterSegment[roots.length];
        for (int i = 0; i < roots.length; i++) {
            final Path root = roots[i];
            final SegmentedPath segmentedPath = (SegmentedPath) root;
            final FileSystemId fsId = (FileSystemId) root.getFileSystem();
            clusterSegments[i] = new ClusterSegment() {
                @Override
                public String getClusterId() {
                    return fsId.id() + CLUSTER_ID_SEGMENT_SEPARATOR + segmentedPath.getSegmentId();
                }

                @Override
                public String[] segmentIds() {
                    return new String[]{segmentedPath.getSegmentId()};
                }
            };
        }
        return clusterSegments;
    }
}
