package xyz.kynu.vip.ui;

public interface UiInformableCallback<T> extends UiCallback<T> {
    void inform(String text);
}
