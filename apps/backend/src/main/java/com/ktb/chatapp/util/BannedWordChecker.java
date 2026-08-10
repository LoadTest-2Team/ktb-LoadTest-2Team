package com.ktb.chatapp.util;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import org.springframework.util.Assert;

public class BannedWordChecker {

    private static class Node {
        private final Map<Character, Node> children = new HashMap<>();
        private Node fail;
        private boolean end;
    }

    private final Node root = new Node();

    public BannedWordChecker(Set<String> bannedWords) {
        Assert.notEmpty(bannedWords, "Banned words set must not be empty");
        bannedWords.stream()
                .filter(word -> word != null && !word.isBlank())
                .map(word -> word.toLowerCase(Locale.ROOT))
                .forEach(this::insert);
        buildFailureLinks();
    }

    private void insert(String word) {
        Node node = root;
        for (int i = 0; i < word.length(); i++) {
            node = node.children.computeIfAbsent(word.charAt(i), c -> new Node());
        }
        node.end = true;
    }

    private void buildFailureLinks() {
        Queue<Node> queue = new ArrayDeque<>();
        for (Node child : root.children.values()) {
            child.fail = root;
            queue.add(child);
        }

        while (!queue.isEmpty()) {
            Node current = queue.poll();
            for (Map.Entry<Character, Node> entry : current.children.entrySet()) {
                char c = entry.getKey();
                Node child = entry.getValue();

                Node failCandidate = current.fail;
                while (failCandidate != null && !failCandidate.children.containsKey(c)) {
                    failCandidate = failCandidate.fail;
                }
                child.fail = (failCandidate != null) ? failCandidate.children.get(c) : root;
                // 짧은 금칙어가 긴 금칙어의 접미사로 나타나는 경우도 실패 링크를 타고 잡히도록 전파
                child.end = child.end || child.fail.end;

                queue.add(child);
            }
        }
    }

    public boolean containsBannedWord(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }

        String normalizedMessage = message.toLowerCase(Locale.ROOT);
        Node node = root;
        for (int i = 0; i < normalizedMessage.length(); i++) {
            char c = normalizedMessage.charAt(i);
            while (node != root && !node.children.containsKey(c)) {
                node = node.fail;
            }
            node = node.children.getOrDefault(c, root);
            if (node.end) {
                return true;
            }
        }
        return false;
    }
}
