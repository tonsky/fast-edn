package better_clojure.edn;

import clojure.lang.Keyword;
import java.util.Arrays;

public class KeywordCache {
  final float loadFactor = 0.75f;
  LeafNode[] data;
  int mask;
  int threshold;
  int length;

  public static class LeafNode {
    public final char[] chars;
    public final Keyword keyword;
    public final int hashcode;
    LeafNode nextNode;
    public LeafNode(char[] chars, final Keyword keyword, final int hc, final LeafNode nn) {
      this.chars = chars;
      this.keyword = keyword;
      this.hashcode = hc;
      this.nextNode = nn;
    }
  };

  // Taken from openjdk ArraysSupport class
  public static int hashCode(final char[] chars, final int start, final int end) {
    int result = 0;
    for (int i = start; i < end; i++) {
      result = 31 * result + chars[i];
    }
    return result;
  }

  public KeywordCache() {
    data = new LeafNode[128];
    mask = data.length - 1;
    this.threshold = (int) loadFactor * data.length;
    this.length = 0;
  }

  void checkResize() {
    if (this.length >= this.threshold) {
      final int newCap = data.length * 2;
      final LeafNode[] newD = new LeafNode[newCap];
      final LeafNode[] oldD = this.data;
      final int oldCap = oldD.length;
      final int mask = newCap - 1;
      for (int idx = 0; idx < oldCap; ++idx) {
        LeafNode lf;
        if ((lf = oldD[idx]) != null) {
          oldD[idx] = null;
          if (lf.nextNode == null) {
            newD[lf.hashcode & mask] = lf;
          } else {
            // https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/util/HashMap.java#L722
            // Because we only allow capacities that are powers of two, we have
            // exactly 2 locations in the new data array where these can go.  We want
            // to avoid writing to any locations more than once and instead make the
            // at most two new linked lists, one for the new high position and one
            // for the new low position.
            LeafNode loHead = null, loTail = null, hiHead = null, hiTail = null;
            while (lf != null) {
              LeafNode e = lf;
              lf = lf.nextNode;
              // Check high bit
              if ((e.hashcode & oldCap) == 0) {
                if (loTail == null)
                  loHead = e;
                else
                  loTail.nextNode = e;
                loTail = e;
              } else {
                if (hiTail == null)
                  hiHead = e;
                else
                  hiTail.nextNode = e;
                hiTail = e;
              }
            }
            if (loHead != null) {
              loTail.nextNode = null;
              newD[idx] = loHead;
            }
            if (hiHead != null) {
              hiTail.nextNode = null;
              newD[idx + oldCap] = hiHead;
            }
          }
        }
      }
      this.threshold = (int) (newCap * this.loadFactor);
      this.mask = mask;
      this.data = newD;
    }
  }

  public Keyword put(final char[] data, final int start, final int end, int slashPos) {
    final int hc = hashCode(data, start, end);
    final int idx = hc & this.mask;
    final LeafNode lastNode = this.data[idx];
    // Avoid unneeded calls to both equals and checkResize
    for (LeafNode e = lastNode; e != null; e = e.nextNode) {
      if (e.hashcode == hc && Arrays.equals(data, start, end, e.chars, 0, e.chars.length)) {
        return e.keyword;
      }
    }

    String ns = slashPos == -1 ? null : new String(data, start, slashPos);
    String name = slashPos == -1 ? new String(data, start, end - start) : new String(data, start + slashPos + 1, end - (start + slashPos + 1));
    final Keyword keyword = Keyword.intern(ns, name);
    this.data[idx] = new LeafNode(Arrays.copyOfRange(data, start, end), keyword, hc, lastNode);
    ++this.length;
    checkResize();
    return keyword;
  }

  public int size() {
    return this.length;
  }
}
