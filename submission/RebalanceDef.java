import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

class RebalanceDef {
    Map<String, Set<Integer>> toSend = new TreeMap<>();
    Set<String> toRemove = new TreeSet<>();

    private String toAdd(Map.Entry<String, Set<Integer>> entry) {
        return String.join(" ", entry.getKey(), Integer.toString(entry.getValue().size()), Global.joinSpaceString(entry.getValue()));
    }

    @Override
    public String toString() {
        return String.join(" ", Integer.toString(toSend.size()), toSend.entrySet().stream().map(this::toAdd).reduce(Global.stringWithSpace).orElse(""), Integer.toString(toRemove.size()), Global.joinSpaceString(toRemove));
    }

    public void addSendFileTo(String file, Set<Integer> shouldHave) {
        toSend.put(file, shouldHave);
    }

    public void addFileToRemove(String file) {
        toRemove.add(file);
    }

    public boolean isEmpty() {
        return toSend.isEmpty() && toRemove.isEmpty();
    }
}
