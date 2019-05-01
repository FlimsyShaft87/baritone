/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.api.utils;

import baritone.api.Settings;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.item.Item;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.registry.IRegistry;

import java.awt.*;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


public class SettingsUtil {
    private static final Path SETTINGS_PATH = Minecraft.getInstance().gameDir.toPath().resolve("baritone").resolve("settings.txt");
    private static final Pattern SETTING_PATTERN = Pattern.compile("^(?<setting>[^ ]+) +(?<value>.+)"); // key and value split by the first space


    private static boolean isComment(String line) {
        return line.startsWith("#") || line.startsWith("//");
    }

    private static void forEachLine(Path file, Consumer<String> consumer) throws IOException {
        try (BufferedReader scan = Files.newBufferedReader(file)) {
            String line;
            while ((line = scan.readLine()) != null) {
                if (line.isEmpty() || isComment(line)) {
                    continue;
                }
                consumer.accept(line);
            }
        }
    }

    public static void readAndApply(Settings settings) {
        try {
            forEachLine(SETTINGS_PATH, line -> {
                Matcher matcher = SETTING_PATTERN.matcher(line);
                if (!matcher.matches()) {
                    System.out.println("Invalid syntax in setting file: " + line);
                    return;
                }

                String settingName = matcher.group("setting").toLowerCase();
                String settingValue = matcher.group("value");
                try {
                    parseAndApply(settings, settingName, settingValue);
                } catch (Exception ex) {
                    System.out.println("Unable to parse line " + line);
                    ex.printStackTrace();
                }
            });
        } catch (NoSuchFileException ignored) {
            System.out.println("Baritone settings file not found, resetting.");
        } catch (Exception ex) {
            System.out.println("Exception while reading Baritone settings, some settings may be reset to default values!");
            ex.printStackTrace();
        }
    }

    public static synchronized void save(Settings settings) {
        try (BufferedWriter out = Files.newBufferedWriter(SETTINGS_PATH)) {
            for (Settings.Setting setting : modifiedSettings(settings)) {
                out.write(settingToString(setting) + "\n");
            }
        } catch (Exception ex) {
            System.out.println("Exception thrown while saving Baritone settings!");
            ex.printStackTrace();
        }
    }

    public static List<Settings.Setting> modifiedSettings(Settings settings) {
        List<Settings.Setting> modified = new ArrayList<>();
        for (Settings.Setting setting : settings.allSettings) {
            if (setting.value == null) {
                System.out.println("NULL SETTING?" + setting.getName());
                continue;
            }
            if (setting.getName().equals("logger")) {
                continue; // NO
            }
            if (setting.value == setting.defaultValue) {
                continue;
            }
            modified.add(setting);
        }
        return modified;
    }

    public static String settingToString(Settings.Setting setting) throws IllegalStateException {
        if (setting.getName().equals("logger")) {
            return "logger";
        }
        Parser io = Parser.getParser(setting.getType());
        if (io == null) {
            throw new IllegalStateException("Missing " + setting.getValueClass() + " " + setting.getName());
        }
        return setting.getName() + " " + io.toString(new ParserContext(setting), setting.value);
    }

    public static void parseAndApply(Settings settings, String settingName, String settingValue) throws IllegalStateException, NumberFormatException {
        Settings.Setting setting = settings.byLowerName.get(settingName);
        if (setting == null) {
            throw new IllegalStateException("No setting by that name");
        }
        Class intendedType = setting.getValueClass();
        ISettingParser ioMethod = Parser.getParser(setting.getType());
        Object parsed = ioMethod.parse(new ParserContext(setting), settingValue);
        if (!intendedType.isInstance(parsed)) {
            throw new IllegalStateException(ioMethod + " parser returned incorrect type, expected " + intendedType + " got " + parsed + " which is " + parsed.getClass());
        }
        setting.value = parsed;
    }

    private interface ISettingParser<T> {

        T parse(ParserContext context, String raw);

        String toString(ParserContext context, T value);

        boolean accepts(Type type);
    }

    private static class ParserContext {

        private final Settings.Setting<?> setting;

        public ParserContext(Settings.Setting<?> setting) {
            this.setting = setting;
        }

        final Settings.Setting<?> getSetting() {
            return this.setting;
        }
    }

    private enum Parser implements ISettingParser {

        DOUBLE(Double.class, Double::parseDouble),
        BOOLEAN(Boolean.class, Boolean::parseBoolean),
        INTEGER(Integer.class, Integer::parseInt),
        FLOAT(Float.class, Float::parseFloat),
        LONG(Long.class, Long::parseLong),
        ENUMFACING(EnumFacing.class, EnumFacing::byName),
        COLOR(
                Color.class,
                str -> new Color(Integer.parseInt(str.split(",")[0]), Integer.parseInt(str.split(",")[1]), Integer.parseInt(str.split(",")[2])),
                color -> color.getRed() + "," + color.getGreen() + "," + color.getBlue()
        ),
        BLOCK(
                Block.class,
                str -> BlockUtils.stringToBlockRequired(str.trim()),
                BlockUtils::blockToString
        ),
        ITEM(
                Item.class,
                str -> IRegistry.ITEM.get(new ResourceLocation(str.trim())),
                item -> IRegistry.ITEM.getKey(item).toString()
        ),
        LIST() {
            @Override
            public Object parse(ParserContext context, String raw) {
                Type type = ((ParameterizedType) context.getSetting().getType()).getActualTypeArguments()[0];
                Parser parser = Parser.getParser(type);
                return Arrays.stream(raw.split(","))
                        .map(s -> parser.parse(context, s))
                        .collect(Collectors.toList());
            }

            @Override
            public String toString(ParserContext context, Object value) {
                Type type = ((ParameterizedType) context.getSetting().getType()).getActualTypeArguments()[0];
                Parser parser = Parser.getParser(type);

                return ((List<?>) value).stream()
                        .map(o -> parser.toString(context, o))
                        .collect(Collectors.joining(","));
            }

            @Override
            public boolean accepts(Type type) {
                return List.class.isAssignableFrom(TypeUtils.resolveBaseClass(type));
            }
        };

        private final Class<?> klass;
        private final Function<String, Object> parser;
        private final Function<Object, String> toString;

        Parser() {
            this.klass = null;
            this.parser = null;
            this.toString = null;
        }

        <T> Parser(Class<T> klass, Function<String, T> parser) {
            this(klass, parser, Object::toString);
        }

        <T> Parser(Class<T> klass, Function<String, T> parser, Function<T, String> toString) {
            this.klass = klass;
            this.parser = parser::apply;
            this.toString = x -> toString.apply((T) x);
        }

        @Override
        public Object parse(ParserContext context, String raw) {
            return this.parser.apply(raw);
        }

        @Override
        public String toString(ParserContext context, Object value) {
            return this.toString.apply(value);
        }

        @Override
        public boolean accepts(Type type) {
            return type instanceof Class && this.klass.isAssignableFrom((Class) type);
        }

        public static Parser getParser(Type type) {
            return Arrays.stream(values())
                    .filter(parser -> parser.accepts(type))
                    .findFirst().orElse(null);
        }
    }
}
