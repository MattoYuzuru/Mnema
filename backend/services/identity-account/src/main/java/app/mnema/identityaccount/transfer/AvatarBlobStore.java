package app.mnema.identityaccount.transfer;

interface AvatarBlobStore {
    byte[] read(String key);

    /** Returns true only when this call created the object. */
    boolean putExact(String key, byte[] bytes);

    void deleteExact(String key);
}
