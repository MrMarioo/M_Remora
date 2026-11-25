package pl.mtech.mremora.agent;

import pl.mtech.mremora.protocol.MouseButton;

/**
 * Sink for remote input events. Coordinates are physical pixels of the captured screen,
 * measured from its top-left corner.
 */
public interface InputInjector {

    void move(int x, int y);

    void press(MouseButton.Button b);

    void release(MouseButton.Button b);

    void wheel(int notches);
}
