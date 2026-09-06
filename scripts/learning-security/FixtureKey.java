import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;

/** Disposable local fixture key; never a deployment key generator. */
class FixtureKey {
    public static void main(String[] args) throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var key = (RSAPrivateCrtKey) generator.generateKeyPair().getPrivate();
        var values = new java.math.BigInteger[]{key.getModulus(), key.getPublicExponent(),
                key.getPrivateExponent(), key.getPrimeP(), key.getPrimeQ(),
                key.getPrimeExponentP(), key.getPrimeExponentQ(), key.getCrtCoefficient()};
        var names = new String[]{"n", "e", "d", "p", "q", "dp", "dq", "qi"};
        var json = new StringBuilder("{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"blackbox\",\"alg\":\"RS256\",\"use\":\"sig\"");
        for (int i = 0; i < names.length; i++) {
            byte[] bytes = values[i].toByteArray();
            if (bytes[0] == 0) bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
            json.append(",\"").append(names[i]).append("\":\"")
                    .append(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)).append('"');
        }
        Files.writeString(Path.of(args[0]), json.append("}]}").toString());
    }
}
