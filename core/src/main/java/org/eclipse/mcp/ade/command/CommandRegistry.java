/*******************************************************************************
 * Copyright (c) 2026 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Angelo ZERR - initial API and implementation
 *******************************************************************************/
package org.eclipse.mcp.ade.command;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

/**
 * Registry that discovers all {@link Command}-annotated methods at runtime
 * and provides metadata and invocation capabilities.
 */
@ApplicationScoped
public class CommandRegistry {

    private final Map<String, CommandEntry> commands = new LinkedHashMap<>();
    private boolean initialized = false;

    @Inject
    Instance<Object> cdiInstances;

    private final List<Object> commandBeans = new ArrayList<>();

    @Inject
    void registerBeans(ExtensionCommands extensionCommands,
                       LspCommands lspCommands,
                       DapCommands dapCommands,
                       BspCommands bspCommands) {
        commandBeans.add(extensionCommands);
        commandBeans.add(lspCommands);
        commandBeans.add(dapCommands);
        commandBeans.add(bspCommands);
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;
        for (Object bean : commandBeans) {
            scanBean(bean);
        }
    }

    private void scanBean(Object bean) {
        // Walk the class hierarchy to find @Command annotations
        // (Quarkus CDI proxies don't carry annotations on the proxy class)
        Class<?> clazz = bean.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Method method : clazz.getDeclaredMethods()) {
                Command cmd = method.getAnnotation(Command.class);
                if (cmd == null) {
                    continue;
                }
                String key = cmd.domain().getValue() + "/" + cmd.action();
                if (commands.containsKey(key)) {
                    continue;
                }
                List<CommandInfo.ArgInfo> args = new ArrayList<>();
                for (Parameter param : method.getParameters()) {
                    CommandArg arg = param.getAnnotation(CommandArg.class);
                    if (arg != null) {
                        String name = arg.name().isEmpty() ? param.getName() : arg.name();
                        args.add(new CommandInfo.ArgInfo(name, arg.description(), arg.required(), arg.defaultValue()));
                    }
                }
                CommandInfo info = new CommandInfo(cmd.domain().getValue(), cmd.action(), cmd.description(), args);
                commands.put(key, new CommandEntry(info, bean, method));
            }
            clazz = clazz.getSuperclass();
        }
    }

    /**
     * Returns metadata for all registered commands.
     */
    public List<CommandInfo> listCommands() {
        ensureInitialized();
        return commands.values().stream()
                .map(CommandEntry::info)
                .toList();
    }

    /**
     * Executes a command by domain and action, passing the given arguments.
     */
    public Object execute(String domain, String action, Map<String, String> args) throws Exception {
        ensureInitialized();
        String key = domain + "/" + action;
        CommandEntry entry = commands.get(key);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown command: " + domain + " " + action);
        }
        Method method = entry.method();
        Parameter[] params = method.getParameters();
        Object[] invokeArgs = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            CommandArg arg = params[i].getAnnotation(CommandArg.class);
            if (arg != null) {
                String name = arg.name().isEmpty() ? params[i].getName() : arg.name();
                invokeArgs[i] = args.get(name);
            }
        }
        return method.invoke(entry.bean(), invokeArgs);
    }

    private record CommandEntry(CommandInfo info, Object bean, Method method) {
    }
}
