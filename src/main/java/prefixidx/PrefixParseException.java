package prefixidx;

/**
 * 模式不合法时抛出的异常：`*` 不是独占一段、`*` 不落在最后一段、段内含空白、
 * 同一模式字符串重复出现等（见 README「对外契约」第 1、3、12 条）。
 *
 * <p>它继承 {@link IllegalArgumentException}，因此「参数非法一律抛
 * IllegalArgumentException」这条口径对模式与路径两侧同时成立。
 *
 * <p>类名与继承关系是对外契约的一部分，不要改动。
 */
public class PrefixParseException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public PrefixParseException(String message) {
        super(message);
    }

    public PrefixParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
