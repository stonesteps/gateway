package com.tritonsvc.messageprocessor.util;

/**
 * Created by holow on 12.10.2016.
 */
public interface WatchedThreadCreator {

    /**
     * This method should cancel currently running thread (if exists) and create new one.
     */
    void recreateThread();
}
