package fast_edn;

import clojure.lang.Keyword;
import java.util.Arrays;

/**
 * Remembers recently read keyword tokens and the interned Keyword they produced, so a repeated keyword skips
 * new String + new Symbol + Keyword.intern (a ConcurrentHashMap lookup).
 *
 * <p>One table per JVM, shared by all parsers. 2-way set associative: a token's hash selects a set of two
 * slots; slot 1 holds the newer keyword, slot 2 the older one. A slot holds
 * <ul>
 *   <li>null (empty),</li>
 *   <li>a bare Keyword: seen once; storing it allocates nothing,</li>
 *   <li>an Entry (char[] copy + Keyword): seen again; fastest to compare.</li>
 * </ul>
 *
 * <p>Only keywords that the parser produced from exactly these chars are stored, and invalid tokens throw
 * before they are stored, so a hit needs no validation.
 *
 * <p>No locks: stored objects are immutable with final fields, so a racing reader sees an old, a new or no
 * object in a slot, and a hit always compares the full token. A race costs at most a miss.
 */
final class KeywordCache {
  private KeywordCache() {}

  private static final int SLOTS    = 4096;      // power of 2, 2 slots per set
  private static final int SET_MASK = SLOTS - 2; // clears bit 0: index of slot 1 of a set (always even)

  /** slot -> null, Keyword or Entry */
  private static final Object[] slots = new Object[SLOTS];

  /**
   * slot -> hash of the stored token. Lets a lookup skip a slot with one int compare instead of following
   * Keyword -> Symbol -> String (CPU cache misses for rarely used keywords). Written separately from
   * {@link #slots}, so the two can be out of step under races; the full compare still decides.
   */
  private static final int[] hashes = new int[SLOTS];

  private static final class Entry {
    final char[]  chars;
    final Keyword keyword;

    Entry(char[] chars, Keyword keyword) {
      this.chars   = chars;
      this.keyword = keyword;
    }
  }

  /**
   * Cheap hash of a non-empty token: length plus the first two, middle and last two chars. Constant cost per
   * token; collisions are fine because a hit compares the whole token.
   */
  static int hash(char[] buf, int start, int end) {
    int len = end - start;
    int h = len;
    h = 31 * h + buf[start];
    h = 31 * h + buf[start + (len > 1 ? 1 : 0)];
    h = 31 * h + buf[start + (len >> 1)];
    h = 31 * h + buf[end - (len > 1 ? 2 : 1)];
    h = 31 * h + buf[end - 1];
    return h;
  }

  private static int set(int h) {
    return (h ^ (h >>> 16)) & SET_MASK;
  }

  /** The cached Keyword for the token buf[start, end), or null. */
  static Keyword get(int h, char[] buf, int start, int end) {
    int slot1 = set(h);
    Keyword kw = lookup(slot1, h, buf, start, end);
    if (kw != null) {
      return kw;
    }
    return lookup(slot1 + 1, h, buf, start, end);
  }

  /** Stores kw in slot 1 of its set; the previous slot 1 moves to slot 2, the previous slot 2 is dropped. */
  static void put(int h, Keyword kw) {
    int slot1 = set(h);
    slots[slot1 + 1]  = slots[slot1];
    hashes[slot1 + 1] = hashes[slot1];
    slots[slot1]      = kw;
    hashes[slot1]     = h;
  }

  /** The Keyword in this slot if it matches the token, else null. A matching bare Keyword is upgraded to an Entry. */
  private static Keyword lookup(int slot, int h, char[] buf, int start, int end) {
    if (hashes[slot] != h) {
      return null;
    }
    Object o = slots[slot];
    if (o instanceof Entry) {
      Entry e = (Entry) o;
      return charsMatch(e.chars, buf, start, end - start) ? e.keyword : null;
    }
    if (o != null) {
      Keyword kw = (Keyword) o;
      if (keywordMatches(kw, buf, start, end - start)) {
        slots[slot] = new Entry(Arrays.copyOfRange(buf, start, end), kw);
        return kw;
      }
    }
    return null;
  }

  private static boolean charsMatch(char[] chars, char[] buf, int start, int len) {
    if (chars.length != len) {
      return false;
    }
    for (int i = 0; i < len; ++i) {
      if (chars[i] != buf[start + i]) {
        return false;
      }
    }
    return true;
  }

  /**
   * Compares a bare Keyword with the token. The parser splits a token at its first '/', so the token is
   * exactly ns + "/" + name, or name without a namespace (":ns//" is ns "ns", name "/").
   */
  private static boolean keywordMatches(Keyword kw, char[] buf, int start, int len) {
    String ns   = kw.sym.getNamespace();
    String name = kw.sym.getName();
    int pos = start;
    if (ns != null) {
      int nsLen = ns.length();
      if (nsLen + 1 + name.length() != len) {
        return false;
      }
      for (int i = 0; i < nsLen; ++i) {
        if (ns.charAt(i) != buf[pos + i]) {
          return false;
        }
      }
      pos += nsLen;
      if (buf[pos] != '/') {
        return false;
      }
      pos += 1;
    } else if (name.length() != len) {
      return false;
    }
    int nameLen = name.length();
    for (int i = 0; i < nameLen; ++i) {
      if (name.charAt(i) != buf[pos + i]) {
        return false;
      }
    }
    return true;
  }
}
