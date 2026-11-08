package dev.aurora.economy.core;

/** A player-safe, stable error code; SQL failures are not exposed in chat. */
public final class EconomyException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public enum Code {
        INVALID_AMOUNT, UNKNOWN_CURRENCY, INSUFFICIENT_FUNDS, DAILY_LIMIT,
        IDEMPOTENCY_CONFLICT, NOT_FOUND, FORBIDDEN, INVALID_STATE, EXPIRED,
        LOCKED, SLIPPAGE, NO_LIQUIDITY, LIMIT_REACHED, STORAGE, INTEGRITY
    }
    private final Code code;
    public EconomyException(Code code, String message) { super(message); this.code = code; }
    public EconomyException(Code code, String message, Throwable cause) { super(message, cause); this.code = code; }
    public Code code() { return code; }
    public static EconomyException invalid(String message) { return new EconomyException(Code.INVALID_AMOUNT, message); }
}
