package com.ecommerce.catalog.application.service;

import com.ecommerce.catalog.application.port.ProductSearchIndex;
import com.ecommerce.catalog.infrastructure.persistence.entity.SearchRebuildEntity;
import com.ecommerce.catalog.infrastructure.persistence.mapper.SearchRebuildMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

@Service
public class CatalogSearchRebuildCutoverService {

    private final SearchRebuildMapper mapper;
    private final ProductSearchIndex index;

    public CatalogSearchRebuildCutoverService(
            SearchRebuildMapper mapper,
            ProductSearchIndex index) {
        this.mapper = mapper;
        this.index = index;
    }

    @Transactional
    public boolean cutover(
            long rebuildId,
            String owner,
            String workingIndex,
            long indexedCount) {
        Instant lockedAt = mapper.currentTime();
        SearchRebuildEntity rebuild = mapper.selectByIdForUpdate(rebuildId);
        if (rebuild == null
                || !"RUNNING".equals(rebuild.getStatus())
                || !Objects.equals(owner, rebuild.getClaimOwner())
                || !Objects.equals(workingIndex, rebuild.getTargetIndex())
                || rebuild.getClaimUntil() == null
                || !rebuild.getClaimUntil().isAfter(lockedAt)) {
            return false;
        }

        // Alias replacement is intentionally kept inside this short row-lock transaction.
        // It prevents a stale-lease reset or a new claimant from crossing the cutover point.
        replaceAliasAndResolveUnknownResult(workingIndex);
        if (mapper.markSucceeded(
                rebuildId,
                owner,
                workingIndex,
                indexedCount,
                lockedAt) != 1) {
            throw new IllegalStateException("Search rebuild completion state changed during cutover");
        }
        return true;
    }

    private void replaceAliasAndResolveUnknownResult(String workingIndex) {
        try {
            index.replaceAlias(workingIndex);
        } catch (RuntimeException switchFailure) {
            try {
                if (index.aliasTargets(workingIndex)) {
                    return;
                }
            } catch (RuntimeException verificationFailure) {
                switchFailure.addSuppressed(verificationFailure);
            }
            throw switchFailure;
        }
    }
}
