package tc.oc.parse.validate;

import java.nio.file.Path;

import tc.oc.parse.ValueException;

public class RelativePath implements Validation<Path> {
    @Override
    public void validate(Path value) throws ValueException {
        if(value.isAbsolute()) {
            throw new ValueException("Path must be relative (cannot start with '/')");
        }
    }
}
