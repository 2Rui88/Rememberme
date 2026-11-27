package memory.longterm;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class MemoryText {
    private MemoryText() {}

    static String normalized(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\s]+", "");
    }

    static String limit(String text, int characters) {
        int length = text.codePointCount(0, text.length());
        return text.substring(0, text.offsetByCodePoints(0, Math.min(characters, length)));
    }

    static boolean similar(String first, String second) {
        String a = normalized(first);
        String b = normalized(second);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b) || a.contains(b) || b.contains(a)) {
            return true;
        }
        int[] left = a.codePoints().toArray();
        int[] right = b.codePoints().toArray();
        if (jaccard(unigrams(left), unigrams(right)) >= .7) {
            return true;
        }
        int length = Math.max(left.length, right.length);
        return length <= 120 && 1.0 - (double) distance(left, right) / length >= .8;
    }

    static double relevance(String query, String content) {
        String left = normalized(query);
        String right = normalized(content);
        if (left.isBlank() || right.isBlank()) {
            return 0;
        }
        if (right.contains(left) || right.length() >= 4 && left.contains(right)) {
            return 1;
        }
        Set<Integer> queryGrams = bigrams(left);
        Set<Integer> contentGrams = bigrams(right);
        int shared = intersection(queryGrams, contentGrams);
        return (double) shared / Math.max(1, Math.min(queryGrams.size(), contentGrams.size()));
    }

    private static Set<Integer> bigrams(String text) {
        Set<Integer> grams = new HashSet<>();
        int[] points = text.codePoints().toArray();
        for (int i = 1; i < points.length; i++) {
            grams.add(31 * points[i - 1] + points[i]);
        }
        return grams;
    }

    private static Set<Integer> unigrams(int[] text) {
        Set<Integer> result = new HashSet<>();
        for (int point : text) {
            result.add(point);
        }
        return result;
    }

    private static double jaccard(Set<Integer> a, Set<Integer> b) {
        int intersection = intersection(a, b);
        return (double) intersection / (a.size() + b.size() - intersection);
    }

    private static int intersection(Set<Integer> a, Set<Integer> b) {
        int shared = 0;
        for (int value : a) {
            if (b.contains(value)) {
                shared++;
            }
        }
        return shared;
    }

    private static int distance(int[] left, int[] right) {
        int[] row = new int[right.length + 1];
        for (int j = 0; j <= right.length; j++) {
            row[j] = j;
        }
        for (int i = 1; i <= left.length; i++) {
            int diagonal = row[0];
            row[0] = i;
            for (int j = 1; j <= right.length; j++) {
                int above = row[j];
                row[j] = Math.min(Math.min(row[j - 1], above) + 1,
                        diagonal + (left[i - 1] == right[j - 1] ? 0 : 1));
                diagonal = above;
            }
        }
        return row[right.length];
    }
}
