package app.mnema.learning.generation.mbm;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Pipe-table row syntax of {@code ::table}: cells are trimmed literal text; only {@code \|} and {@code \\} escape. */
final class TableText {

    private static final Pattern SEPARATOR_CELL = Pattern.compile(":?-+:?");

    private TableText() {
    }

    /** The cells of a row that starts with {@code |}; an optional trailing pipe does not add an empty cell. */
    static List<String> cells(String row) {
        var cells = new ArrayList<String>();
        var current = new StringBuilder();
        for (int p = 1; p < row.length(); p++) {
            char c = row.charAt(p);
            if (c == '\\' && p + 1 < row.length() && (row.charAt(p + 1) == '|' || row.charAt(p + 1) == '\\')) {
                current.append(row.charAt(++p));
            } else if (c == '|') {
                cells.add(current.toString().strip());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (cells.isEmpty() || !current.toString().isBlank()) {
            cells.add(current.toString().strip());
        }
        return cells;
    }

    /** Whether every cell is {@code -+} with optional colons: the row under the header. */
    static boolean isSeparator(List<String> cells) {
        return cells.stream().allMatch(cell -> SEPARATOR_CELL.matcher(cell).matches());
    }
}
