package com.tritonsvc.messageprocessor.notifications;

import java.io.IOException;

/**
 * Created by holow on 18.10.2016.
 */
public interface ApnsSenderBuilder {

    /**
     * Constructs ApnsSender instance.
     *
     * @return
     * @throws IOException
     */
    ApnsSender build() throws IOException;

}
