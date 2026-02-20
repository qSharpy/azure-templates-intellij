package com.bogdanbujor.azuretemplates.core

import kotlin.math.abs
import kotlin.math.min

/**
 * Fuzzy search engine that matches a query against file paths.
 *
 * Supports:
 * - Substring matching (query characters found in order within the path)
 * - Typo tolerance via Levenshtein-like distance on individual path segments
 * - Bonus scoring for consecutive character matches, word-boundary matches,
 *   and exact segment matches
 *
 * The algorithm searches the **full workspace-relative path**, so queries like
 * "build", "release", or "utils" match directory names, not just filenames.
 */
object FuzzySearch {

    data class SearchResult(
        val filePath: String,
        val relativePath: String,
        val score: Int
    )

    /**
     * Searches [candidates] (workspace-relative paths mapped to absolute paths)
     * for entries that fuzzy-match [query]. Returns results sorted best-first.
     *
     * @param query       the user's search string (may contain typos)
     * @param candidates  map of absolute-path → relative-path for all indexed files
     * @param maxResults  maximum number of results to return
     */
    fun search(
        query: String,
        candidates: Map<String, String>,
        maxResults: Int = 20
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        val lowerQuery = query.lowercase()

        return candidates.mapNotNull { (absPath, relPath) ->
            val score = score(lowerQuery, relPath.lowercase(), relPath)
            if (score > 0) SearchResult(absPath, relPath, score) else null
        }
            .sortedByDescending { it.score }
            .take(maxResults)
    }

    /**
     * Computes a fuzzy match score for [query] against [lowerPath] (lowercased)
     * and [originalPath] (original casing, used for word-boundary detection).
     *
     * Returns 0 if there is no reasonable match.
     */
    private fun score(query: String, lowerPath: String, originalPath: String): Int {
        // Strategy 1: Subsequence match with scoring
        val subseqScore = subsequenceScore(query, lowerPath, originalPath)

        // Strategy 2: Segment-level fuzzy match (handles typos within a single segment)
        val segmentScore = segmentFuzzyScore(query, lowerPath)

        // Strategy 3: Contiguous substring match (very strong signal)
        val containsScore = if (lowerPath.contains(query)) {
            200 + (100 - query.length) // shorter queries that match exactly are still good
        } else 0

        return maxOf(subseqScore, segmentScore, containsScore)
    }

    /**
     * Subsequence matching: tries to match each query character in order within the path.
     * Awards bonus points for:
     * - Consecutive matches
     * - Matches at word boundaries (after `/`, `-`, `_`, `.`)
     * - Matches at the start of the path
     */
    private fun subsequenceScore(query: String, lowerPath: String, originalPath: String): Int {
        var score = 0
        var pathIdx = 0
        var prevMatchIdx = -2 // so first match isn't "consecutive"
        var matched = 0

        for (qChar in query) {
            var found = false
            while (pathIdx < lowerPath.length) {
                if (lowerPath[pathIdx] == qChar) {
                    matched++
                    score += 10 // base score per matched char

                    // Consecutive bonus
                    if (pathIdx == prevMatchIdx + 1) {
                        score += 15
                    }

                    // Word boundary bonus
                    if (pathIdx == 0 || lowerPath[pathIdx - 1] in listOf('/', '-', '_', '.', ' ')) {
                        score += 20
                    }

                    // Camel-case boundary bonus
                    if (pathIdx > 0 && originalPath[pathIdx].isUpperCase() && originalPath[pathIdx - 1].isLowerCase()) {
                        score += 15
                    }

                    prevMatchIdx = pathIdx
                    pathIdx++
                    found = true
                    break
                }
                pathIdx++
            }
            if (!found) {
                // Not all query chars matched — penalize but don't disqualify if most matched
                break
            }
        }

        // Require at least 60% of query characters to match
        if (matched < (query.length * 0.6).toInt().coerceAtLeast(1)) return 0

        // Bonus for matching all characters
        if (matched == query.length) score += 30

        // Penalty for unmatched query characters
        score -= (query.length - matched) * 15

        return score.coerceAtLeast(0)
    }

    /**
     * Segment-level fuzzy matching: splits the path into segments (by `/`, `-`, `_`, `.`)
     * and checks if the query is a fuzzy match for any segment. This handles typos
     * like "eru" matching "run", "templete" matching "template", etc.
     *
     * Uses a modified Levenshtein distance that also considers substring matching
     * within segments.
     */
    private fun segmentFuzzyScore(query: String, lowerPath: String): Int {
        val segments = lowerPath.split('/', '-', '_', '.')
            .filter { it.isNotEmpty() }

        var bestScore = 0

        for (segment in segments) {
            // Exact segment match
            if (segment == query) {
                bestScore = maxOf(bestScore, 300)
                continue
            }

            // Segment starts with query
            if (segment.startsWith(query)) {
                bestScore = maxOf(bestScore, 250)
                continue
            }

            // Segment contains query as substring
            if (segment.contains(query)) {
                bestScore = maxOf(bestScore, 200)
                continue
            }

            // Query contains segment as substring (e.g., query "buildtemplate" contains segment "build")
            if (query.contains(segment) && segment.length >= 3) {
                bestScore = maxOf(bestScore, 150)
                continue
            }

            // Fuzzy match using edit distance — allow typos
            // For short queries (<=3), allow 1 typo; for longer queries, allow up to 2
            val maxDistance = when {
                query.length <= 2 -> 0  // too short for fuzzy
                query.length <= 4 -> 1
                else -> 2
            }

            if (maxDistance > 0) {
                // Check if query is a fuzzy substring of the segment
                val dist = fuzzySubstringDistance(query, segment)
                if (dist in 1..maxDistance) {
                    val fuzzyScore = 150 - (dist * 40) // 110 for 1 typo, 70 for 2 typos
                    bestScore = maxOf(bestScore, fuzzyScore)
                }
            }
        }

        return bestScore
    }

    /**
     * Computes the minimum edit distance between [query] and any substring of [text]
     * of similar length. This allows finding "run" in "rerun" with 0 distance,
     * or "eru" as a near-match for "run" with distance 2.
     *
     * For efficiency, uses a sliding-window Levenshtein approach.
     */
    private fun fuzzySubstringDistance(query: String, text: String): Int {
        if (query.isEmpty()) return text.length
        if (text.isEmpty()) return query.length

        val m = query.length
        val n = text.length

        // Standard Levenshtein but with free start position in text (semi-global alignment)
        // dp[i][j] = edit distance between query[0..i-1] and text[0..j-1]
        // Initialize first row to 0 (free start in text)
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = 0 // free start

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (query[i - 1] == text[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,       // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }

        // Find minimum in last row (free end in text)
        return dp[m].min()
    }
}
