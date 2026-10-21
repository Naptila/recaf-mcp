package me.naptila.recafmcp.mcp.android;

import software.coley.recaf.info.FileInfo;
import software.coley.recaf.workspace.model.bundle.BasicFileBundle;

/** Resolves exact lookups to Recaf's normal typed files without marking a read as an edit. */
final class DeferredFileBundle extends BasicFileBundle {
    @Override
    public FileInfo get(Object name) {
        FileInfo current = super.get(name);
        if (!(current instanceof DeferredFileInfo deferred)) return current;
        FileInfo resolved = deferred.resolve();
        synchronized (this) {
            if (super.get(name) == deferred) {
                var history = getHistory(deferred.getName());
                if (history != null && history.size() == 1 && history.peek() == deferred) {
                    initialPut(resolved);
                    return resolved;
                }
            }
            return super.get(name);
        }
    }
}
