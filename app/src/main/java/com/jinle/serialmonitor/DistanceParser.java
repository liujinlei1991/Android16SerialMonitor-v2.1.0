
package com.jinle.serialmonitor;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DistanceParser {
    interface Listener {
        void onLine(int port, String line, Double distanceMm);
    }

    private static final String NUMBER = "([+-]?\\d+(?:\\.\\d+)?)";
    private static final Pattern LABELED_DISTANCE = Pattern.compile(
            "(?:distances?|dist|range)\\s*[:=]\\s*" + NUMBER +
                    "\\s*(mm|cm|m)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER_WITH_UNIT = Pattern.compile(
            NUMBER + "\\s*(mm|cm|m)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_DISTANCE = Pattern.compile(
            "^\\s*" + NUMBER + "\\s*(mm|cm|m)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    // A complete mm/cm suffix lets us safely publish a value without waiting for
    // a line ending. A lone "m" is intentionally excluded because it might be
    // the first half of an "mm" suffix split across two USB packets.
    private static final Pattern COMPLETE_STREAM_VALUE = Pattern.compile(
            "(?:(?:distances?|dist|range)\\s*[:=]\\s*)?" + NUMBER +
                    "\\s*(mm|cm)",
            Pattern.CASE_INSENSITIVE);

    private final StringBuilder[] buffers = new StringBuilder[8];
    private final Listener listener;

    DistanceParser(Listener listener) {
        this.listener = listener;
        for (int i = 0; i < buffers.length; i++) buffers[i] = new StringBuilder();
    }

    synchronized void accept(int port, byte[] data, int offset, int length) {
        if (port < 0 || port >= buffers.length || length <= 0) return;
        StringBuilder buffer = buffers[port];
        String incoming = new String(data, offset, length, StandardCharsets.UTF_8)
                .replace('\0', ' ');
        buffer.append(incoming);

        int delimiter;
        while ((delimiter = firstDelimiter(buffer)) >= 0) {
            String line = buffer.substring(0, delimiter).trim();
            int deleteTo = delimiter + 1;
            while (deleteTo < buffer.length()) {
                char next = buffer.charAt(deleteTo);
                if (next != '\r' && next != '\n') break;
                deleteTo++;
            }
            buffer.delete(0, deleteTo);
            emit(port, line);
        }

        // Some radar firmware continuously sends "distances=268mm" without
        // CR/LF. Publish every complete token immediately so the UI stays live.
        Matcher streamMatcher;
        while ((streamMatcher = COMPLETE_STREAM_VALUE.matcher(buffer)).find()) {
            String token = streamMatcher.group();
            Double distance = parse(streamMatcher.group(1), streamMatcher.group(2));
            buffer.delete(0, streamMatcher.end());
            listener.onLine(port, token, distance);
        }

        if (buffer.length() > 4096) {
            String line = buffer.toString().trim();
            buffer.setLength(0);
            emit(port, line);
        }
    }

    private static int firstDelimiter(StringBuilder buffer) {
        for (int i = 0; i < buffer.length(); i++) {
            char value = buffer.charAt(i);
            if (value == '\r' || value == '\n') return i;
        }
        return -1;
    }

    private void emit(int port, String line) {
        if (line.isEmpty()) return;
        Double distance = findDistance(line);
        listener.onLine(port, line, distance);
    }

    private static Double findDistance(String line) {
        Matcher matcher = LABELED_DISTANCE.matcher(line);
        if (matcher.find()) return parse(matcher.group(1), matcher.group(2));

        matcher = NUMBER_WITH_UNIT.matcher(line);
        if (matcher.find()) return parse(matcher.group(1), matcher.group(2));

        matcher = BARE_DISTANCE.matcher(line);
        if (matcher.matches()) return parse(matcher.group(1), matcher.group(2));
        return null;
    }

    private static Double parse(String number, String unit) {
        try {
            double value = Double.parseDouble(number);
            if (unit == null || unit.equalsIgnoreCase("mm")) return value;
            if (unit.equalsIgnoreCase("cm")) return value * 10.0;
            if (unit.equalsIgnoreCase("m")) return value * 1000.0;
        } catch (NumberFormatException ignored) {
        }
        return null;
    }
}
