/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 hsz Jakub Chrzanowski <jakub@hsz.mobi>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package mobi.hsz.idea.gitignore.outer;

import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeRegistry;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.messages.MessageBusConnection;
import git4idea.ignore.lang.GitExcludeFileType;
import git4idea.ignore.lang.GitIgnoreFileType;
import mobi.hsz.idea.gitignore.file.type.IgnoreFileType;
import mobi.hsz.idea.gitignore.lang.IgnoreLanguage;
import mobi.hsz.idea.gitignore.lang.kind.GitExcludeLanguage;
import mobi.hsz.idea.gitignore.lang.kind.GitLanguage;
import mobi.hsz.idea.gitignore.lang.kind.MercurialLanguage;
import mobi.hsz.idea.gitignore.settings.IgnoreSettings;
import mobi.hsz.idea.gitignore.util.Utils;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.zmlx.hg4idea.ignore.lang.HgIgnoreFileType;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static mobi.hsz.idea.gitignore.settings.IgnoreSettings.KEY;

/**
 * Component loader for outer ignore files editor.
 *
 * @author Jakub Chrzanowski <jakub@hsz.mobi>
 * @since 1.1
 */
public class OuterIgnoreLoaderComponent implements ProjectComponent {
    /** Current project. */
    private final Project project;

    /** MessageBus instance. */
    private MessageBusConnection messageBus;

    /**
     * Returns {@link OuterIgnoreLoaderComponent} service instance.
     *
     * @param project current project
     * @return {@link OuterIgnoreLoaderComponent instance}
     */
    @NotNull
    public static OuterIgnoreLoaderComponent getInstance(@NotNull final Project project) {
        return project.getComponent(OuterIgnoreLoaderComponent.class);
    }

    /** Constructor. */
    public OuterIgnoreLoaderComponent(@NotNull final Project project) {
        this.project = project;
    }

    /**
     * Returns component name.
     *
     * @return component name
     */
    @Override
    @NonNls
    @NotNull
    public String getComponentName() {
        return "IgnoreOuterComponent";
    }

    /** Initializes component. */
    @Override
    public void initComponent() {
        messageBus = project.getMessageBus().connect();
        messageBus.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new IgnoreEditorManagerListener(project));
    }

    @Override
    public void disposeComponent() {
        if (messageBus != null) {
            messageBus.disconnect();
            messageBus = null;
        }
    }

    /** Listener for ignore editor manager. */
    private static class IgnoreEditorManagerListener implements FileEditorManagerListener {
        /** Current project. */
        private final Project project;

        /** Constructor. */
        public IgnoreEditorManagerListener(@NotNull final Project project) {
            this.project = project;
        }

        /**
         * Handles file opening event and attaches outer ignore component.
         *
         * @param source editor manager
         * @param file   current file
         */
        @Override
        public void fileOpened(@NotNull final FileEditorManager source, @NotNull final VirtualFile file) {
            final FileType fileType = file.getFileType();
            if (!IgnoreSettings.getInstance().isOuterIgnoreRules()) {
                return;
            }

            IgnoreLanguage language = determineIgnoreLanguage(file, fileType);
            if (language == null) {
                return;
            }

            DumbService.getInstance(project).runWhenSmart(() -> {
                final List<VirtualFile> outerFiles = new ArrayList<>(language.getOuterFiles(project, false));
                if (language instanceof GitLanguage) {
                    outerFiles.addAll(GitExcludeLanguage.INSTANCE.getOuterFiles(project));
                    ContainerUtil.removeDuplicates(outerFiles);
                }
                if (outerFiles.isEmpty() || outerFiles.contains(file)) {
                    return;
                }

                for (final FileEditor fileEditor : source.getEditors(file)) {
                    if (fileEditor instanceof TextEditor) {
                        final OuterIgnoreWrapper wrapper = new OuterIgnoreWrapper(project, language, outerFiles);
                        final JComponent component = wrapper.getComponent();
                        final IgnoreSettings.Listener settingsListener = (key, value) -> {
                            if (KEY.OUTER_IGNORE_RULES.equals(key)) {
                                component.setVisible((Boolean) value);
                            }
                        };

                        IgnoreSettings.getInstance().addListener(settingsListener);
                        source.addBottomComponent(fileEditor, component);

                        Disposer.register(fileEditor, wrapper);
                        Disposer.register(fileEditor, () -> {
                            IgnoreSettings.getInstance().removeListener(settingsListener);
                            source.removeBottomComponent(fileEditor, component);
                        });
                    }
                }
            });
        }

        /**
         * If language provided by platform (e.g. GitLanguage) then map to language provided by plugin
         * with extended functionality.
         *
         * @param file     file to check
         * @param fileType file's FileType
         * @return mapped language
         */
        @Nullable
        private IgnoreLanguage determineIgnoreLanguage(@NotNull VirtualFile file, FileType fileType) {
            FileTypeRegistry typeRegistry = FileTypeRegistry.getInstance();
            if (Utils.isGitPluginEnabled()) {
                if (typeRegistry.isFileOfType(file, GitIgnoreFileType.INSTANCE)) {
                    return GitLanguage.INSTANCE;
                }
                if (typeRegistry.isFileOfType(file, GitExcludeFileType.INSTANCE)) {
                    return GitExcludeLanguage.INSTANCE;
                }
            } else if (Utils.isMercurialPluginEnabled() && typeRegistry.isFileOfType(file, HgIgnoreFileType.INSTANCE)) {
                return MercurialLanguage.INSTANCE;
            } else if (fileType instanceof IgnoreFileType) {
                return ((IgnoreFileType) fileType).getIgnoreLanguage();
            }
            return null;
        }

        @Override
        public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        }

        @Override
        public void selectionChanged(@NotNull FileEditorManagerEvent event) {
        }
    }

    /** Outer file fetcher event interface. */
    public interface OuterFileFetcher {
        @NotNull
        Collection<VirtualFile> fetch(@NotNull Project project);
    }
}
