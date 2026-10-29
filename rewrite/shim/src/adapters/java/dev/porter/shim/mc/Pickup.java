// porter:adapter (the mod-specific, PRIVATE) - BucketPickup marker for the pipes batch: an interface (unlike the java.lang.Object
// that r-more-heavyb-type-bucketpickup maps to) so that "interface ILavaLoggable extends BucketPickup, ..." stays valid.
// Pumps.isPickup/pickupBlock take Object, so they accept it.
package dev.porter.shim.mc;

public interface Pickup {
}
