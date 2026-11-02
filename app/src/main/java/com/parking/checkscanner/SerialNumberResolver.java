package com.parking.checkscanner;

import java.util.Map;

/** Require agreement, and never resolve a tied vote using the import slot's default number. */
final class SerialNumberResolver {
    static int resolve(Map<Integer, Integer> votes) {
        int best = -1, count = 0, runnerUp = 0;
        for (Map.Entry<Integer, Integer> entry : votes.entrySet()) {
            if (entry.getKey() < 1 || entry.getKey() > 500) continue;
            int n = entry.getValue();
            if (n > count) {
                runnerUp = count;
                count = n;
                best = entry.getKey();
            } else if (n > runnerUp) runnerUp = n;
        }
        return count >= 2 && count > runnerUp ? best : -1;
    }
}
