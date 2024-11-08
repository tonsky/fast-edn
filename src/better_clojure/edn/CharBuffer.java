package better_clojure.edn;

public final class CharBuffer {
  char[] buffer;
  int len;

  public CharBuffer() {
    buffer = new char[32];
    len = 0;
  }

  public final char[] ensureCapacity(int newlen) {
    if (newlen >= buffer.length) {
      char[] newbuffer = new char[newlen * 2];
      System.arraycopy(buffer, 0, newbuffer, 0, len);
      buffer = newbuffer;
    }
    return buffer;
  }

  public final void append(char val) {
    char[] buf = buffer;
    // common case first
    if (len < buf.length) {
      buf[len] = val;
      ++len;
      return;
    }
    buf = ensureCapacity(len + 1);
    buf[len] = val;
    ++len;
  }

  public final void append(char[] data, int startoff, int endoff) {
    if (startoff < endoff) {
      int buflen = len;
      final int nchars = endoff - startoff;
      final int newlen = buflen + nchars;
      final char[] buf = ensureCapacity(newlen);

      if (nchars < 5) {
        for (; startoff < endoff; ++startoff, ++buflen) buf[buflen] = data[startoff];
      } else {
        System.arraycopy(data, startoff, buf, buflen, nchars);
      }
      len = newlen;
    }
  }

  public final void append(CharSequence s) {
    final int nchars = s.length();
    if (nchars > 0) {
      int buflen = len;
      final int newlen = buflen + nchars;
      final char[] buf = ensureCapacity(newlen);
      for (int idx = 0; idx < nchars; ++idx, ++buflen) buf[buflen] = s.charAt(idx);
      len = newlen;
    }
  }

  public final void clear() {
    len = 0;
  }

  public char[] buffer() {
    return buffer;
  }

  public final int length() {
    return len;
  }

  public final int capacity() {
    return buffer.length;
  }

  public char charAt(int idx) {
    return buffer[idx];
  }

  public final String toString(char[] buffer, int sidx, int eidx) {
    if (len == 0) {
      return new String(buffer, sidx, eidx - sidx);
    } else {
      append(buffer, sidx, eidx);
      return toString();
    }
  }

  public final String toString() {
    int strlen = len;
    int startoff = 0;
    if (strlen == 0) {
      return "";
    } else {
      return new String(buffer, startoff, strlen);
    }
  }
}
