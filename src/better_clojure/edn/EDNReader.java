package better_clojure.edn;

import clojure.lang.ITransientCollection;
import clojure.lang.ITransientMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentHashMap;
import clojure.lang.PersistentVector;
import clojure.lang.Util;
import java.io.EOFException;
import java.math.BigDecimal;
import java.math.BigInteger;

public final class EDNReader {
  CharReader reader;
  public final boolean throwOnEOF;
  public final Object eofValue;
  public final KeywordCache keywordCache = new KeywordCache();
  final CharBuffer charBuffer = new CharBuffer();

  public EDNReader(boolean _throwOnEOF, Object _eofValue) {
    throwOnEOF = _throwOnEOF;
    eofValue = _eofValue;
  }

  public static boolean numberChar(char v) {
    return (v >= '0' && v <= '9') || v == '-';
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
              throw new Exception("JSON parse error - Unrecognized escape character: " + data);
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
    throw new EOFException("JSON parse error - EOF while reading string: " + charBuffer.toString());
  }

  final Object finalizeNumber(CharBuffer cb, boolean integer, final char firstChar, final int dotIndex)
      throws Exception {
    final char[] cbBuffer = cb.buffer();
    final int nElems = cb.length();
    if (integer) {
      // Definitely an integer
      if ((nElems > 1 && firstChar == '0') || (nElems > 2 && firstChar == '-' && cbBuffer[1] == '0'))
        throw new Exception("JSON parse error - integer starting with 0: " + cb.toString());
      if (nElems == 1) {
        long retval = Character.digit(cbBuffer[0], 10);
        if (retval < 0)
          throw new Exception("JSON parse error - invalid integer: " + cb.toString());
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
          throw new Exception("JSON parse error - period must be preceded and followed by a digit: " + strdata);
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
        if (Character.isWhitespace(nextChar) || nextChar == ']' || nextChar == '}' || nextChar == ',') {
          charBuffer.append(buffer, startpos, pos);
          // Not we do not increment position here as the next method
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
    return reader.context(200);
  }

  public final Object readList() throws Exception {
    boolean hasNext = true;
    boolean first = true;
    ITransientCollection aryObj = PersistentVector.EMPTY.asTransient();
    while (!reader.eof()) {
      final char nextChar = reader.eatwhite();
      if (nextChar == ']') {
        if (hasNext && !first)
          throw new Exception("JSON parse error - One too many commas in your list my friend");
        return aryObj.persistent();
      } else if (nextChar != 0) {
        if (!hasNext)
          throw new Exception("JSON parse error - One too few commas in your list my friend");
        first = false;
        reader.unread();
        aryObj = aryObj.conj(readObject());
        hasNext = reader.eatwhite() == ',';
        if (!hasNext)
          reader.unread();
      }
    }
    throw new EOFException("JSON parse error - EOF while reading list");
  }

  public final Object readMap() throws Exception {
    boolean hasNext = true;
    boolean first = true;
    ITransientMap mapObj = PersistentHashMap.EMPTY.asTransient();
    while (!reader.eof()) {
      char nextChar = reader.eatwhite();
      if (nextChar == '}') {
        if (hasNext && !first)
          throw new Exception("JSON parse error - One too many commas in your map my friend: "
              + String.valueOf(mapObj.persistent()) + "context:\n" + context());
        return mapObj.persistent();
      } else {
        first = false;
        if (!hasNext)
          throw new Exception("JSON parse error - One too few commas in your map my friend: "
              + String.valueOf(mapObj.persistent()) + "context:\n" + context());
        Object keyVal = null;
        if (nextChar == '"')
          keyVal = readString();
        else
          throw new Exception("JSON parse error - JSON keys must be quoted strings.");

        nextChar = reader.eatwhite();
        if (nextChar != ':')
          throw new Exception("JSON parse error - Map keys must be followed by a ':'");
        Object valVal = readObject();
        mapObj = mapObj.assoc(keyVal, valVal);
        nextChar = reader.eatwhite();
        if (nextChar == 0)
          throw new EOFException(
              "JSON parse error - EOF while reading map: " + String.valueOf(mapObj.persistent()));
        hasNext = nextChar == ',';
        if (!hasNext)
          reader.unread();
      }
    }
    throw new EOFException("JSON parse error - EOF while reading map.");
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
          throw new Exception("JSON parse error - bad boolean value.");
        }
        case 'f': {
          final char[] data = tempRead(4);
          if (data[0] == 'a' && data[1] == 'l' && data[2] == 's' && data[3] == 'e')
            return false;
          throw new Exception("JSON parse error - bad boolean value.");
        }
        case 'n': {
          final char[] data = tempRead(3);
          if (data[0] == 'u' && data[1] == 'l' && data[2] == 'l')
            return null;
          throw new Exception(
              "JSON parse error - unrecognized 'null' entry - " + new String(data) + " - context:\n" + context());
        }
        case '[': {
          return readList();
        }
        case '{': {
          return readMap();
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
          throw new Exception("JSON parse error - Unexpected character - " + val);
      }
    }
  }

  public void beginParse(CharReader rdr) {
    reader = rdr;
  }
}
