package dev.waythread;

enum RouteAlignment {
    ON_ROUTE, AT_BOUNDARY, DETACHED;

    boolean waitingToRejoin() {
        return this != ON_ROUTE;
    }
}
