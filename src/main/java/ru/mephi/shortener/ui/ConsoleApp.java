package ru.mephi.shortener.ui;

import ru.mephi.shortener.core.Link;
import ru.mephi.shortener.core.Notice;
import ru.mephi.shortener.core.ShortCode;
import ru.mephi.shortener.core.Shortener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.UUID;

public final class ConsoleApp {
    private static final String HELP = """
            shorten <URL> [лимит]      Создать ссылку
            <короткая ссылка или код> Открыть в браузере
            open <ссылка или код>     Открыть в браузере
            use <UUID>                Войти в профиль по UUID
            new                       Начать сеанс нового пользователя
            whoami                    Показать UUID текущего пользователя
            list                      Показать свои ссылки, лимиты и сроки действия
            limit <ссылка> <лимит>     Установить общий лимит переходов
            url <ссылка> <URL>         Изменить адрес назначения
            delete <ссылка>            Удалить свою ссылку
            help                      Показать команды
            exit                      Выйти

            Пример: shorten https://www.baeldung.com/java-9-http-client 3""";

    private final Shortener shortener;
    private final Shortener.Browser browser;
    private final PrintWriter output;
    private UUID owner;

    public ConsoleApp(Shortener shortener, Shortener.Browser browser, PrintWriter output) {
        this.shortener = shortener;
        this.browser = browser;
        this.output = output;
    }

    public void run(BufferedReader input) throws IOException {
        output.println("Сокращение ссылок. Введите help для списка команд.");
        for (String line; (line = input.readLine()) != null; ) {
            if (!execute(line.strip())) {
                return;
            }
        }
    }

    public synchronized void tick() {
        try {
            shortener.expire();
            showNotices();
        } catch (IOException ex) {
            output.println("Ошибка фонового обслуживания: " + ex.getMessage());
        }
    }

    private synchronized boolean execute(String line) {
        if (line.isEmpty()) {
            return true;
        }
        String[] words = line.split("\\s+");
        try {
            switch (words[0]) {
                case "shorten" -> create(words);
                case "use" -> use(argument(words, "use <UUID>"));
                case "new" -> {
                    checkUsage(words, 1, "new");
                    owner = null;
                    output.println("Новый UUID будет создан при сокращении первой ссылки");
                }
                case "whoami" -> {
                    checkUsage(words, 1, "whoami");
                    output.println(owner == null ? "Текущий UUID ещё не задан" : "UUID: " + owner);
                }
                case "list" -> {
                    checkUsage(words, 1, "list");
                    var links = shortener.list(owner);
                    if (links.isEmpty()) {
                        output.println("У вас пока нет ссылок");
                    }
                    links.forEach(this::showLink);
                }
                case "open" -> open(argument(words, "open <ссылка>"));
                case "limit" -> {
                    checkUsage(words, 3, "limit <ссылка> <лимит>");
                    showLink(shortener.setLimit(owner, words[1], parseLimit(words[2])));
                }
                case "url" -> {
                    checkUsage(words, 3, "url <ссылка> <URL>");
                    showLink(shortener.setDestination(owner, words[1], words[2]));
                }
                case "delete" -> {
                    shortener.delete(owner, argument(words, "delete <ссылка>"));
                    output.println("Ссылка удалена");
                }
                case "help" -> {
                    checkUsage(words, 1, "help");
                    output.println(HELP);
                }
                case "exit" -> {
                    checkUsage(words, 1, "exit");
                    return false;
                }
                default -> {
                    if (words.length == 1 && ShortCode.accepts(line)) {
                        open(line);
                    } else {
                        throw new IllegalArgumentException("Неизвестная команда. Введите help");
                    }
                }
            }
        } catch (IOException | IllegalArgumentException ex) {
            output.println("Ошибка: " + ex.getMessage());
        }
        try {
            showNotices();
        } catch (IOException ex) {
            output.println("Не удалось прочитать уведомления: " + ex.getMessage());
        }
        return true;
    }

    private void create(String[] words) throws IOException {
        if (words.length != 2 && words.length != 3) {
            throw new IllegalArgumentException("Использование: shorten <URL> [лимит]");
        }
        Link link = shortener.create(owner, words[1], words.length == 3 ? parseLimit(words[2]) : null);
        if (owner == null) {
            owner = link.owner();
            output.println("Ваш UUID: " + owner + ". Сохраните его для команды use");
        }
        showLink(link);
    }

    private void use(String text) {
        UUID selected;
        try {
            selected = UUID.fromString(text);
            if (!selected.toString().equalsIgnoreCase(text)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Некорректный UUID", ex);
        }
        if (!shortener.hasOwner(selected)) {
            throw new IllegalArgumentException("Пользователь с этим UUID не найден");
        }
        owner = selected;
        output.println("Активный UUID: " + owner);
    }

    private void open(String code) throws IOException {
        shortener.open(code, browser);
        output.println("Ссылка отправлена в браузер");
    }

    private void showLink(Link link) {
        output.printf("%s -> %s | переходы %d/%d | действует до %s%s%n",
                ShortCode.PREFIX + link.code(), link.destination(), link.clicks(), link.limit(), link.expiresAt(),
                link.exhausted() ? " | лимит исчерпан" : "");
    }

    private void showNotices() throws IOException {
        if (owner == null) {
            return;
        }
        for (Notice notice : shortener.takeNotices(owner)) {
            String reason = switch (notice.reason()) {
                case LIMIT_REACHED -> "лимит переходов исчерпан";
                case EXPIRED -> "срок действия истёк, ссылка удалена";
            };
            output.printf("Уведомление: %s — %s (%s)%n", ShortCode.PREFIX + notice.code(), reason, notice.at());
        }
    }

    private static String argument(String[] words, String usage) {
        checkUsage(words, 2, usage);
        return words[1];
    }

    private static void checkUsage(String[] words, int count, String usage) {
        if (words.length != count) {
            throw new IllegalArgumentException("Использование: " + usage);
        }
    }

    private static int parseLimit(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Лимит должен быть целым числом", ex);
        }
    }
}
