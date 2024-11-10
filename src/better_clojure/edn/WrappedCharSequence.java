package better_clojure.edn;

public class WrappedCharSequence implements CharSequence {
    public final char[] array;

    public WrappedCharSequence(char[] array) {
        this.array = array;
    }

    @Override
    public int length() {
        return array.length;
    }

    @Override
    public char charAt(int index) {
        return array[index];
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String toString() {
        return new String(array);
    }
}

