package better_clojure.edn;

import clojure.lang.ATransientMap;
import clojure.lang.ATransientSet;
import clojure.lang.IPersistentList;
import clojure.lang.ITransientCollection;
import clojure.lang.Keyword;
import clojure.lang.LazilyPersistentVector;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentHashMap;
import clojure.lang.PersistentHashSet;
import clojure.lang.PersistentList;
import clojure.lang.PersistentVector;
import clojure.lang.Util;
import java.io.EOFException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.ListIterator;
import java.util.stream.Collectors;

@SuppressWarnings("unchecked")
public final class EDNReader {
  CharReader reader;
  public final boolean throwOnEOF;
  public final Object eofValue;
  public final KeywordCache keywordCache = new KeywordCache();
  public final CharBuffer charBuffer = new CharBuffer();

  public EDNReader(boolean _throwOnEOF, Object _eofValue) {
    throwOnEOF = _throwOnEOF;
    eofValue = _eofValue;
  }

  public static boolean numberChar(char v) {
    return (v >= '0' && v <= '9') || v == '-' || v == '+';
  }
  public static boolean isAsciiDigit(char v) {
    return v >= '0' && v <= '9';
  }

  final char[] tempRead(int nchars) throws EOFException {
    final char[] tempBuf = new char[nchars];
    if (reader.read(tempBuf, 0, nchars) == -1)
      throw new EOFException();
    return tempBuf;
  }

  final Object readString() throws Exception {
    charBuffer.clear();
    char[] buffer = reader.buffer();
    while (buffer != null) {
      int startpos = reader.position();
      int len = buffer.length;
      for (int pos = startpos; pos < len; ++pos) {
        final char curChar = buffer[pos];
        if (curChar == '"') {
          final Object rv = charBuffer.toString(buffer, startpos, pos);
          reader.position(pos + 1);
          return rv;
        } else if (curChar == '\\') {
          charBuffer.append(buffer, startpos, pos);
          final int idata = reader.readFrom(pos + 1);
          if (idata == -1)
            throw new EOFException();
          final char data = (char) idata;
          switch (data) {
            case '"':
            case '\\':
            case '/':
              charBuffer.append(data);
              break;
            case 'b':
              charBuffer.append('\b');
              break;
            case 'f':
              charBuffer.append('\f');
              break;
            case 'r':
              charBuffer.append('\r');
              break;
            case 'n':
              charBuffer.append('\n');
              break;
            case 't':
              charBuffer.append('\t');
              break;
            case 'u':
              final char[] temp = tempRead(4);
              charBuffer.append((char) (Integer.parseInt(new String(temp, 0, 4), 16)));
              break;
            default:
              throw new Exception("Parse error - Unrecognized escape character: " + data);
          }
          buffer = reader.buffer();
          startpos = reader.position();
          // pos will be incremented in loop update
          pos = startpos - 1;
        }
      }
      charBuffer.append(buffer, startpos, len);
      buffer = reader.nextBuffer();
    }
    throw new EOFException("Parse error - EOF while reading string: " + charBuffer.toString());
  }

  final Object finalizeNumber(CharBuffer cb, boolean integer, final char firstChar, final int dotIndex)
      throws Exception {
    final char[] cbBuffer = cb.buffer();
    final int nElems = cb.length();
    if (integer) {
      // Definitely an integer
      if ((nElems > 1 && firstChar == '0') || (nElems > 2 && firstChar == '-' && cbBuffer[1] == '0'))
        throw new Exception("Parse error - integer starting with 0: " + cb.toString());
      if (nElems == 1) {
        long retval = Character.digit(cbBuffer[0], 10);
        if (retval < 0)
          throw new Exception("Parse error - invalid integer: " + cb.toString());
        return retval;
      } else {
        final String strdata = cb.toString();
        if (nElems < 18)
          return Long.parseLong(strdata);
        else {
          try {
            return Long.parseLong(strdata);
          } catch (Exception e) {
            return new BigInteger(strdata);
          }
        }
      }
    } else {
      final String strdata = cb.toString();
      if (dotIndex != -1) {
        final char bufChar = cbBuffer[dotIndex];
        // sanity check
        if (bufChar != '.')
          throw new RuntimeException(
              "Programming error - dotIndex incorrect: " + String.valueOf(dotIndex) + " - " + strdata);
        // If there is a period it must have a number on each side.
        if (dotIndex == nElems - 1 || !isAsciiDigit(cbBuffer[dotIndex + 1]) || dotIndex == 0
            || !isAsciiDigit(cbBuffer[dotIndex - 1]))
          throw new Exception("Parse error - period must be preceded and followed by a digit: " + strdata);
      }
      return Double.parseDouble(strdata);
    }
  }

  final Object readNumber(final char firstChar) throws Exception {
    charBuffer.clear();
    charBuffer.append(firstChar);
    boolean integer = true;
    char[] buffer = reader.buffer();
    int dotIndex = -1;
    while (buffer != null) {
      int startpos = reader.position();
      int pos = startpos;
      int len = buffer.length;
      for (; pos < len; ++pos) {
        final char nextChar = buffer[pos];
        if (CharReader.isBoundary(nextChar)) {
          charBuffer.append(buffer, startpos, pos);
          // Note that we do not increment position here as the next method
          // needs to restart parsing from this place.
          reader.position(pos);
          return finalizeNumber(charBuffer, integer, firstChar, dotIndex);
        } else if (nextChar == 'e' || nextChar == 'E' || nextChar == '.') {
          if (nextChar == '.') {
            // if appending in blocks
            dotIndex = charBuffer.length() + pos - startpos;
            // if appending in singles
            // dotIndex = charBuffer.length() - 1;
          }
          integer = false;
        }
      }
      charBuffer.append(buffer, startpos, pos);
      buffer = reader.nextBuffer();
    }
    return finalizeNumber(charBuffer, integer, firstChar, dotIndex);
  }

  public final String context() throws Exception {
    String context = reader.context(200);
    if (context != null) {
      return ", context:\n" + context;
    } else {
      return "";
    }
  }

  public final Object readList() throws Exception {
    ArrayList acc = new ArrayList();
    while (!reader.eof()) {
      final char nextChar = reader.eatwhite();
      if (nextChar == ')') {
        IPersistentList res = PersistentList.EMPTY;
        for(ListIterator i = acc.listIterator(acc.size()); i.hasPrevious(); ) {
          res = (IPersistentList) res.cons(i.previous());
        }
        return res;
      } else if (nextChar == 0) {
        break;
      } else {
        reader.unread();
        acc.add(readObject());
      }
    }
    throw Util.runtimeException("EOF while reading list: " + acc.stream().map(String::valueOf).collect(Collectors.joining(" ", "(", ")")) + context());
  }

  public final Object readVector() throws Exception {
    ITransientCollection acc = PersistentVector.EMPTY.asTransient();
    while (!reader.eof()) {
      final char nextChar = reader.eatwhite();
      if (nextChar == ']') {
        return acc.persistent();
      } else if (nextChar == 0) {
        break;
      } else {
        reader.unread();
        acc = acc.conj(readObject());
      }
    }
    throw Util.runtimeException("EOF while reading vector: " + String.valueOf(acc.persistent()) + context());
  }

  public final Object readSet() throws Exception {
    ATransientSet acc = (ATransientSet) PersistentHashSet.EMPTY.asTransient();
    int count = 0;
    while (!reader.eof()) {
      final char nextChar = reader.eatwhite();
      if (nextChar == '}') {
        return acc.persistent();
      } else if (nextChar == 0) {
        break;
      } else {
        reader.unread();
        Object key = readObject();
        acc = (ATransientSet) acc.conj(key);
        int newCount = acc.count();
        if (count + 1 != newCount) {
          throw new IllegalArgumentException("Duplicate key: " + key + " reading set: " + String.valueOf(acc.persistent()) + context());
        }
        count = newCount;
      }
    }
    throw Util.runtimeException("EOF while reading set: " + String.valueOf(acc.persistent()) + context());
  }

  public final Object readMap() throws Exception {
    ATransientMap acc = (ATransientMap) PersistentArrayMap.EMPTY.asTransient();
    while (!reader.eof()) {
      char nextChar = reader.eatwhite();
      if (nextChar == '}') {
        return acc.persistent();
      } else if (nextChar == 0) {
        break;
      } else {
        reader.unread();
        // read key
        Object key = readObject();
        if (acc.containsKey(key)) {
          throw new IllegalArgumentException("Duplicate key: " + key + " reading map: " + String.valueOf(acc.persistent()) + context());
        }

        nextChar = reader.eatwhite();
        if (nextChar == '}') {
          throw Util.runtimeException("Map literal must contain an even number of forms: " + String.valueOf(acc.persistent()) + context());
        }
        reader.unread();

        // read value
        Object val = readObject();
        acc = (ATransientMap) acc.assoc(key, val);
      }
    }
    throw Util.runtimeException("EOF while reading map: " + String.valueOf(acc.persistent()) + context());
  }

  public final Object readObject() throws Exception {
    if (reader == null)
      return null;

    final char val = reader.eatwhite();
    if (numberChar(val)) {
      return readNumber(val);
    } else {
      switch (val) {
        case '"':
          return readString();
        case 't': {
          final char[] data = tempRead(3);
          if (data[0] == 'r' && data[1] == 'u' && data[2] == 'e')
            return true;
          throw new Exception("Parse error - bad boolean value.");
        }
        case 'f': {
          final char[] data = tempRead(4);
          if (data[0] == 'a' && data[1] == 'l' && data[2] == 's' && data[3] == 'e')
            return false;
          throw new Exception("Parse error - bad boolean value.");
        }
        case 'n': {
          final char[] data = tempRead(2);
          if (data[0] == 'i' && data[1] == 'l')
            return null;
          throw new Exception(
              "Parse error - unrecognized 'null' entry - " + new String(data) + " - context:\n" + context());
        }
        case '{': {
          return readMap();
        }
        case '[': {
          return readVector();
        }
        case '(': {
          return readList();
        }
        case '#': {
          final int nextChar = reader.read();
          if (nextChar == -1) {
            throw Util.runtimeException("EOF while reading dispatch macro");
          }
          if (nextChar == '{') {
            return readSet();
          }
          throw Util.runtimeException("No dispatch macro for: #" + Character.toString(nextChar));
        }
        case 0:
          if (reader.eof()) {
            if (throwOnEOF) {
              throw Util.runtimeException("EOF while reading");
            } else {
              return eofValue;
            }
          }
          // fallthrough intentional
        default:
          throw new Exception("Parse error - Unexpected character - " + val);
      }
    }
  }

  public void beginParse(CharReader rdr) {
    reader = rdr;
  }
}
