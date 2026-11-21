package dev.waythread;

final class KeyPress {
    private final long holdMillis;
    private State state = State.UP;
    private long pressedAt;

    KeyPress(long holdMillis) {
        this.holdMillis = holdMillis;
    }

    Action update(boolean clicked, boolean down, boolean enabled, long now) {
        if (!enabled) {
            state = down ? State.BLOCKED : State.UP;
            return Action.NONE;
        }
        if (!down) {
            Action action = Action.NONE;
            if (state == State.PRESSED) {
                action = now - pressedAt >= holdMillis ? Action.HOLD : Action.TAP;
            } else if (state == State.UP && clicked) {
                action = Action.TAP;
            }
            state = State.UP;
            return action;
        }
        if (state == State.UP && clicked) {
            if (holdMillis == 0) {
                state = State.HANDLED;
                return Action.TAP;
            }
            state = State.PRESSED;
            pressedAt = now;
        }
        if (state == State.PRESSED && now - pressedAt >= holdMillis) {
            state = State.HANDLED;
            return Action.HOLD;
        }
        return Action.NONE;
    }

    boolean capturesKey() {
        return state == State.HANDLED || state == State.BLOCKED;
    }

    enum Action {
        NONE, TAP, HOLD
    }

    private enum State {
        UP, PRESSED, HANDLED, BLOCKED
    }
}
