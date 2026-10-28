package com.adobe.support.scrlockdump.core.lockdump;

/**
 * Service provided by a delayed component whose activation is deliberately slow.
 */
public interface SlowActivationService {

    /**
     * @return a short description including when the component finished activating
     */
    String describe();
}
