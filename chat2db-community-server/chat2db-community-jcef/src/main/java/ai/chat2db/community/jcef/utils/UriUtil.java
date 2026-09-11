package ai.chat2db.community.jcef.utils;

import ai.chat2db.community.tools.runtime.ProductRuntimeIdentityProvider;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class UriUtil {

    public static URI processInput(String inputString) {
        System.out.println("---");
        System.out.println("Processing input: " + inputString);
        URI finalUri = null;
        String productProtocol = ProductRuntimeIdentityProvider.current().protocolScheme();
        if (inputString != null && inputString.startsWith(productProtocol + "://")) {
            System.out.println("Input type: custom protocol URI");
            try {
                finalUri = new URI(inputString);
                System.out.println("Parsed successfully. Scheme: " + finalUri.getScheme() + ", Host: " + finalUri.getHost());

            } catch (URISyntaxException e) {
                System.err.println("Error: invalid custom protocol URI: " + e.getMessage());
            }
        }
        else {
            System.out.println("Input type: file path");
            try {
                Path path = Paths.get(inputString);
                finalUri = path.toUri();
                System.out.println("Parsed successfully. Generated file URI: " + finalUri);

            } catch (InvalidPathException e) {
                System.err.println("Error: invalid file path: " + e.getMessage());
            }
        }
        return finalUri;
    }

}
