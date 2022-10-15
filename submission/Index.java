import java.util.*;

public class Index {

    Map<String, FileGetTracker> fileAllocation;
    private List<Integer> dStores;



    // index initializers
    public Index(int replication) {
        this.fileAllocation = new TreeMap<>();
        this.dStores = new ArrayList<>();
        this.R = replication;
    }

    protected int dSttorePosition = 0;

    public Index(TreeMap<String, ArrayList<Integer>> fileAlloc1, ArrayList<Integer> dStores, int R) {
        this.fileAllocation = new TreeMap<>();
        fileAlloc1.forEach((filename, ds) -> fileAllocation.put(filename, new FileGetTracker(ds)));
        this.dStores = dStores;
        this.R = R;
    }

    protected final int R;
    //adding a dstore
    public void addDstore(Integer dStore) {
        dStores.add(dStore);
    }


    // is there less dstore than needed
    public boolean notEnoughDstores() {
        return dStores.size() < R;
    }

    // check if it contains the file
    public boolean containsFile(String name) {
        return fileAllocation.containsKey(name);
    }

    public Collection<Integer> getDStoresForFile(String fileName) {
        return fileAllocation.get(fileName).dStores;
    }

    // remove a file
    public void removeFile(String fileName) {
        fileAllocation.remove(fileName);
    }

    // returning the dstores
    public Collection<Integer> getDStores() {
        return dStores;
    }




    public Integer chooseDStoreForLoad(String fileName) {
        return fileAllocation.get(fileName).getNextDStore();
    }


    public Set<Integer> store(String file) {

        List<Integer> newDstores = selectNewOnes();
        fileAllocation.put(file, new FileGetTracker(newDstores));
        return Set.copyOf(newDstores);
    }

    public Integer getNextDStore() {
        dSttorePosition = dSttorePosition % dStores.size();
        Integer next = dStores.get(dSttorePosition);
        dSttorePosition = dSttorePosition + 1;
        return next;
    }

    public List<Integer> selectNewOnes() {
        ArrayList<Integer> newDstores = new ArrayList<>();
        for (int i = R; i > 0; --i) {
            newDstores.add(getNextDStore());
        }
        return newDstores;

    }

    public Index rebalanced() {
        Index newIndex = new Index(R);
        newIndex.dStores = this.dStores;
        for (var f: this.getFiles()) newIndex.store(f);
        return newIndex;
    }

    public Set<String> getFiles() {
        return fileAllocation.keySet();
    }


    // kinda sorta works, it should work
    public Map<String, Index.Diff<Integer>> diff(Index other) {

        Map<String, Index.Diff<Integer>> allChanges = new TreeMap<>();
        for (String name: getFiles()) {
            allChanges.put(name, new Diff());
            Set<Integer> before = Set.copyOf(other.fileAllocation.get(name).dStores);
            Set<Integer> now = Set.copyOf(fileAllocation.get(name).dStores);
            Set<Integer> newStores = Global.setDifference(now, before);
            Set<Integer> deleteFromDstores = Global.setDifference(before, now);

            Index.Diff<Integer> thisFileDiff = allChanges.get(name);
            thisFileDiff.toAdd = newStores;
            thisFileDiff.toRemove = deleteFromDstores;
        }
        // what it needs to return
        return allChanges;
    }

    // check if it equals
    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        if (this == o) return true;
        Index index = (Index) o;
        return Objects.equals(fileAllocation, index.fileAllocation) && Set.of(dStores).equals(Set.of(index.dStores));
    }

    @Override
    public int hashCode() {
        return 0;
    }

    //VERY useful
    static class Diff<T> {
        Set<T> toAdd;
        Set<T> toRemove;
    }

    // sometimes it does not give the right output need to fix this
    static class FileGetTracker {
        List<Integer> dStores;
        int dStoreIndex = 0;

        public FileGetTracker(List<Integer> dStores) {
            this.dStores = dStores;
        }
        //up to this point it works fine
        //get the next dstore
        public Integer getNextDStore() {
            dStoreIndex = (dStoreIndex + 1) % dStores.size();
            return dStores.get(dStoreIndex);
        }
    }
}
