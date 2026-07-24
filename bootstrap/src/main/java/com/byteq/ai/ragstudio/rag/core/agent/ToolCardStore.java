package com.byteq.ai.ragstudio.rag.core.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ToolCardStore {

    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock readLock = rwLock.readLock();
    private final Lock writeLock = rwLock.writeLock();
    private final List<ToolCard> cards = new ArrayList<>();

    public void rebuild(List<ToolCard> newCards) {
        writeLock.lock();
        try {
            cards.clear();
            cards.addAll(newCards);
        } finally {
            writeLock.unlock();
        }
    }

    public int size() {
        readLock.lock();
        try {
            return cards.size();
        } finally {
            readLock.unlock();
        }
    }

    public List<ToolCard> search(float[] queryEmbedding, int topK) {
        readLock.lock();
        try {
            if (cards.isEmpty() || queryEmbedding == null) return List.of();

            List<ScoredCard> scored = new ArrayList<>(cards.size());
            for (ToolCard card : cards) {
                scored.add(new ScoredCard(card, cosineSimilarity(queryEmbedding, card.getEmbedding())));
            }

            scored.sort(Comparator.<ScoredCard>comparingDouble(s -> s.score).reversed());
            int limit = Math.min(topK, scored.size());
            List<ToolCard> result = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                result.add(scored.get(i).card);
            }
            return result;
        } finally {
            readLock.unlock();
        }
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    private record ScoredCard(ToolCard card, double score) {}
}
