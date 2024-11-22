package fast_edn;

public class WrappedCharSequence implements CharSequence {
    public final char[] array;
    public final int start;
    public final int end;

    public WrappedCharSequence(char[] array) {
        this.array = array;
        this.start = 0;
        this.end = array.length;
    }

    public WrappedCharSequence(char[] array, int start, int end) {
        this.array = array;
        this.start = start;
        this.end = end;
    }

    @Override
    public int length() {
        return end - start;
    }

    @Override
    public char charAt(int index) {
        return array[start + index];
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return new String(array, this.start + start, this.start + end - start);
    }

    @Override
    public String toString() {
        return new String(array, start, end - start);
    }
}

