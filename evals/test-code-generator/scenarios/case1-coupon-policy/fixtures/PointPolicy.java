package shop;

public class PointPolicy {
    private final int rate;
    public PointPolicy(int rate) { this.rate = rate; }
    public long earn(long amount) { return amount * rate / 100; }
}
