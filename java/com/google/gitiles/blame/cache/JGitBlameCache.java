package com.google.gitiles.blame.cache;

import com.google.common.cache.Cache;
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.blame.cache.BlameCache;
import org.eclipse.jgit.blame.cache.CacheRegion;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

public class JGitBlameCache implements BlameCache {
    private final Cache<BlameCacheImpl.Key, List<Region>> gitilesCache;

    JGitBlameCache(Cache<BlameCacheImpl.Key, List<Region>> gitilesCache) {
        this.gitilesCache = gitilesCache;
    }

    @Nullable
    @Override
    public List<CacheRegion> get(Repository repo, ObjectId commitId, String path) throws IOException {
        BlameCacheImpl.Key key = new BlameCacheImpl.Key(commitId, path);
        List<Region> cached = gitilesCache.getIfPresent(key);
        if (cached == null) {
            return null;
        }
        return cached.stream().map(JGitBlameCache::cacheGitilesToJgitRegion).collect(Collectors.toUnmodifiableList());
    }

    private static CacheRegion cacheGitilesToJgitRegion(Region gitilesRegion) {
        return new CacheRegion(gitilesRegion.getSourcePath(),
                gitilesRegion.getSourceCommit(),
                gitilesRegion.getSourceAuthor(),
                gitilesRegion.getStart(),
                gitilesRegion.getEnd());
    }
}
