package com.hotel.booking;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

public final class HotelBookingApplication {
    private static final List<Room> ROOMS = List.of(
            new Room("101", "Garden Room", "Standard", 120, 2),
            new Room("102", "City View", "Deluxe", 180, 2),
            new Room("201", "Family Suite", "Suite", 280, 4),
            new Room("202", "Twin Room", "Standard", 140, 2),
            new Room("103", "Courtyard Room", "Standard", 125, 2),
            new Room("104", "Cozy King", "Standard", 130, 2),
            new Room("105", "Patio Room", "Standard", 135, 2),
            new Room("106", "Sunroom Standard", "Standard", 140, 2),
            new Room("D103", "Balcony Deluxe", "Deluxe", 185, 2),
            new Room("D104", "Corner Deluxe", "Deluxe", 190, 2),
            new Room("D105", "Skyline Deluxe", "Deluxe", 195, 2),
            new Room("D106", "Terrace Deluxe", "Deluxe", 200, 2),
            new Room("D107", "Harbor Deluxe", "Deluxe", 210, 2),
            new Room("S202", "Garden Suite", "Suite", 285, 4),
            new Room("S203", "Penthouse Suite", "Suite", 300, 4),
            new Room("S204", "Spa Suite", "Suite", 295, 4),
            new Room("S205", "Sunset Suite", "Suite", 305, 4),
            new Room("S206", "Grand Suite", "Suite", 310, 4),
            new Room("F301", "Family Garden Room", "Family", 320, 6),
            new Room("F302", "Bunkhouse Family Room", "Family", 325, 6),
            new Room("F303", "Connecting Family Room", "Family", 340, 6),
            new Room("F304", "Family Courtyard Room", "Family", 330, 6),
            new Room("F305", "Family Balcony Room", "Family", 350, 6),
            new Room("F306", "Grand Family Room", "Family", 360, 6)
    );

    private HotelBookingApplication() {
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Path bookingsFile = Path.of(System.getenv().getOrDefault("BOOKINGS_FILE", "data/bookings.tsv"));
        BookingStore store = new BookingStore(bookingsFile);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", exchange -> handle(exchange, store));
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.printf("Hotel Booking System listening on port %d; bookings stored in %s%n",
                port, bookingsFile.toAbsolutePath());
    }

    private static void handle(HttpExchange exchange, BookingStore store) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals("/healthz") && method.equals("GET")) {
                sendJson(exchange, 200, "{\"status\":\"ok\"}");
            } else if (path.equals("/api/rooms") && method.equals("GET")) {
                listRooms(exchange, store);
            } else if (path.equals("/api/bookings") && method.equals("GET")) {
                listBookings(exchange, store);
            } else if (path.equals("/api/bookings") && method.equals("POST")) {
                createBooking(exchange, store);
            } else if (path.startsWith("/api/bookings/") && path.endsWith("/extend")
                    && method.equals("POST")) {
                extendBooking(exchange, store, path);
            } else if (path.startsWith("/api/bookings/") && path.endsWith("/cancel")
                    && method.equals("POST")) {
                cancelBooking(exchange, store, path);
            } else if ((path.equals("/") || path.equals("/index.html")) && method.equals("GET")) {
                sendPage(exchange);
            } else {
                sendJson(exchange, 404, "{\"error\":\"Not found\"}");
            }
        } catch (BadRequestException exception) {
            sendJson(exchange, 400, jsonObject("error", exception.getMessage()));
        } catch (NotFoundException exception) {
            sendJson(exchange, 404, jsonObject("error", exception.getMessage()));
        } catch (ConflictException exception) {
            sendJson(exchange, 409, jsonObject("error", exception.getMessage()));
        } catch (IOException exception) {
            System.err.println("Request failed: " + exception.getMessage());
            sendJson(exchange, 500, "{\"error\":\"Unable to save booking data\"}");
        } finally {
            exchange.close();
        }
    }

    private static void listRooms(HttpExchange exchange, BookingStore store) throws IOException {
        Map<String, String> query = parseForm(exchange.getRequestURI().getRawQuery());
        LocalDate checkIn = parseDate(query.get("checkIn"), "check-in");
        LocalDate checkOut = parseDate(query.get("checkOut"), "check-out");
        validateDates(checkIn, checkOut);

        StringBuilder result = new StringBuilder("{\"rooms\":[");
        for (int i = 0; i < ROOMS.size(); i++) {
            Room room = ROOMS.get(i);
            if (i > 0) {
                result.append(',');
            }
            result.append("{\"id\":").append(jsonString(room.id()))
                    .append(",\"name\":").append(jsonString(room.name()))
                    .append(",\"type\":").append(jsonString(room.type()))
                    .append(",\"pricePerNight\":").append(room.pricePerNight())
                    .append(",\"capacity\":").append(room.capacity())
                    .append(",\"available\":").append(store.isAvailable(room.id(), checkIn, checkOut))
                    .append('}');
        }
        sendJson(exchange, 200, result.append("]}").toString());
    }

    private static void listBookings(HttpExchange exchange, BookingStore store) throws IOException {
        String email = parseForm(exchange.getRequestURI().getRawQuery()).get("email");
        if (email == null || email.isBlank()) {
            throw new BadRequestException("Enter the email used for the booking.");
        }
        List<Booking> bookings = store.findByEmail(email.trim());
        StringBuilder result = new StringBuilder("{\"bookings\":[");
        for (int i = 0; i < bookings.size(); i++) {
            if (i > 0) {
                result.append(',');
            }
            result.append(toJson(bookings.get(i)));
        }
        sendJson(exchange, 200, result.append("]}").toString());
    }

    private static void createBooking(HttpExchange exchange, BookingStore store) throws IOException {
        Map<String, String> form = parseForm(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
        String name = required(form, "name", "Guest name");
        String email = required(form, "email", "Email");
        String roomId = required(form, "roomId", "Room");
        LocalDate checkIn = parseDate(form.get("checkIn"), "check-in");
        LocalDate checkOut = parseDate(form.get("checkOut"), "check-out");
        int guests;
        try {
            guests = Integer.parseInt(required(form, "guests", "Guest count"));
        } catch (NumberFormatException exception) {
            throw new BadRequestException("Enter a valid guest count.");
        }

        if (name.length() > 100 || email.length() > 254
                || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new BadRequestException("Enter a valid name and email address.");
        }
        validateDates(checkIn, checkOut);
        if (checkIn.isBefore(LocalDate.now())) {
            throw new BadRequestException("Check-in must be today or a future date.");
        }
        Room room = ROOMS.stream().filter(candidate -> candidate.id().equals(roomId))
                .findFirst().orElseThrow(() -> new BadRequestException("Select a valid room."));
        if (guests < 1 || guests > room.capacity()) {
            throw new BadRequestException("The selected room cannot accommodate that many guests.");
        }

        Booking booking = new Booking(UUID.randomUUID().toString(), name, email, room.id(),
                room.name(), checkIn, checkOut, guests, demoTransactionId());
        store.create(booking);
        sendJson(exchange, 201, "{\"booking\":" + toJson(booking) + "}");
    }

    private static void cancelBooking(HttpExchange exchange, BookingStore store, String path)
            throws IOException {
        String bookingId = path.substring("/api/bookings/".length(),
                path.length() - "/cancel".length());
        if (bookingId.isBlank() || bookingId.contains("/")) {
            throw new NotFoundException("Booking not found.");
        }
        Map<String, String> form = parseForm(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
        String email = required(form, "email", "Email");
        Booking booking = store.cancel(bookingId, email.trim());
        int amount = bookingAmount(booking);
        sendJson(exchange, 200, "{\"message\":\"Booking cancelled.\",\"originalAmount\":"
                + amount + ",\"cancellationFee\":" + (amount - amount / 2)
                + ",\"refundedAmount\":" + amount / 2
                + ",\"refundTransactionId\":" + jsonString(demoTransactionId()) + "}");
    }

    private static void extendBooking(HttpExchange exchange, BookingStore store, String path)
            throws IOException {
        String bookingId = path.substring("/api/bookings/".length(),
                path.length() - "/extend".length());
        if (bookingId.isBlank() || bookingId.contains("/")) {
            throw new NotFoundException("Booking not found.");
        }
        Map<String, String> form = parseForm(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
        String email = required(form, "email", "Email");
        LocalDate newCheckOut = parseDate(form.get("checkOut"), "new check-out");
        Booking previous = store.find(bookingId, email.trim());
        if (!newCheckOut.isAfter(previous.checkOut())) {
            throw new BadRequestException("The new check-out must be after your current check-out.");
        }
        validateDates(previous.checkIn(), newCheckOut);
        BookingExtension extension = store.extend(bookingId, email.trim(), newCheckOut);
        Booking updated = extension.booking();
        Room room = roomForBooking(updated);
        int additionalAmount = Math.toIntExact(
                java.time.temporal.ChronoUnit.DAYS.between(extension.previousCheckOut(), newCheckOut)
                        * room.pricePerNight());
        sendJson(exchange, 200, "{\"booking\":" + toJson(updated)
                + ",\"additionalAmount\":" + additionalAmount + "}");
    }

    private static void sendPage(HttpExchange exchange) throws IOException {
        try (var input = HotelBookingApplication.class.getResourceAsStream("/public/index.html")) {
            if (input == null) {
                sendJson(exchange, 500, "{\"error\":\"Application page is missing\"}");
                return;
            }
            byte[] body = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline';");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String toJson(Booking booking) {
        Room room = roomForBooking(booking);
        return "{\"id\":" + jsonString(booking.id())
                + ",\"name\":" + jsonString(booking.name())
                + ",\"email\":" + jsonString(booking.email())
                + ",\"roomId\":" + jsonString(booking.roomId())
                + ",\"roomName\":" + jsonString(booking.roomName())
                + ",\"checkIn\":" + jsonString(booking.checkIn().toString())
                + ",\"checkOut\":" + jsonString(booking.checkOut().toString())
                + ",\"guests\":" + booking.guests()
                + ",\"pricePerNight\":" + room.pricePerNight()
                + ",\"totalAmount\":" + bookingAmount(booking)
                + ",\"transactionId\":" + jsonString(booking.transactionId()) + "}";
    }

    private static String demoTransactionId() {
        return "DEMO-" + UUID.randomUUID().toString().toUpperCase();
    }

    private static String legacyTransactionId(String bookingId) {
        return "DEMO-" + UUID.nameUUIDFromBytes(bookingId.getBytes(StandardCharsets.UTF_8))
                .toString().toUpperCase();
    }

    private static int bookingAmount(Booking booking) {
        Room room = roomForBooking(booking);
        return Math.toIntExact(java.time.temporal.ChronoUnit.DAYS.between(
                booking.checkIn(), booking.checkOut()) * room.pricePerNight());
    }

    private static Room roomForBooking(Booking booking) {
        return ROOMS.stream().filter(room -> room.id().equals(booking.roomId()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "Booking references unknown room " + booking.roomId()));
    }

    private static String jsonObject(String key, String value) {
        return "{" + jsonString(key) + ":" + jsonString(value) + "}";
    }

    private static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static String required(Map<String, String> form, String key, String label) {
        String value = form.get(key);
        if (value == null || value.isBlank()) {
            throw new BadRequestException(label + " is required.");
        }
        return value.trim();
    }

    private static LocalDate parseDate(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Choose a " + label + " date.");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new BadRequestException("Enter a valid " + label + " date.");
        }
    }

    private static void validateDates(LocalDate checkIn, LocalDate checkOut) {
        if (!checkOut.isAfter(checkIn)) {
            throw new BadRequestException("Check-out must be after check-in.");
        }
        if (checkIn.plusDays(365).isBefore(checkOut)) {
            throw new BadRequestException("A stay cannot exceed 365 nights.");
        }
    }

    private static Map<String, String> parseForm(String encoded) {
        Map<String, String> values = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return values;
        }
        for (String pair : encoded.split("&")) {
            int separator = pair.indexOf('=');
            String key = decode(separator < 0 ? pair : pair.substring(0, separator));
            String value = decode(separator < 0 ? "" : pair.substring(separator + 1));
            values.put(key, value);
        }
        return values;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("The request contains invalid form data.");
        }
    }

    private record Room(String id, String name, String type, int pricePerNight, int capacity) {
    }

    private record Booking(String id, String name, String email, String roomId, String roomName,
                           LocalDate checkIn, LocalDate checkOut, int guests, String transactionId) {
    }

    private record BookingExtension(Booking booking, LocalDate previousCheckOut) {
    }

    private static final class BookingStore {
        private final Path file;
        private final Map<String, Booking> bookings = new LinkedHashMap<>();

        private BookingStore(Path file) throws IOException {
            this.file = file.toAbsolutePath();
            Path parent = this.file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Files.exists(this.file)) {
                load();
            }
        }

        private synchronized boolean isAvailable(String roomId, LocalDate checkIn, LocalDate checkOut) {
            return bookings.values().stream()
                    .filter(booking -> booking.roomId().equals(roomId))
                    .noneMatch(booking -> checkIn.isBefore(booking.checkOut())
                            && checkOut.isAfter(booking.checkIn()));
        }

        private synchronized List<Booking> findByEmail(String email) {
            return bookings.values().stream()
                    .filter(booking -> booking.email().equalsIgnoreCase(email))
                    .sorted(Comparator.comparing(Booking::checkIn))
                    .toList();
        }

        private synchronized Booking find(String id, String email) {
            Booking booking = bookings.get(id);
            if (booking == null || !booking.email().equalsIgnoreCase(email)) {
                throw new NotFoundException("Booking not found for that email address.");
            }
            return booking;
        }

        private synchronized void create(Booking booking) throws IOException {
            if (!isAvailable(booking.roomId(), booking.checkIn(), booking.checkOut())) {
                throw new ConflictException("That room is no longer available for those dates.");
            }
            bookings.put(booking.id(), booking);
            try {
                save();
            } catch (IOException exception) {
                bookings.remove(booking.id());
                throw exception;
            }
        }

        private synchronized Booking cancel(String id, String email) throws IOException {
            Booking booking = find(id, email);
            bookings.remove(id);
            try {
                save();
            } catch (IOException exception) {
                bookings.put(id, booking);
                throw exception;
            }
            return booking;
        }

        private synchronized BookingExtension extend(String id, String email, LocalDate newCheckOut)
                throws IOException {
            Booking booking = find(id, email);
            if (!newCheckOut.isAfter(booking.checkOut())) {
                throw new BadRequestException("The new check-out must be after your current check-out.");
            }
            if (!isAvailableExcept(booking.roomId(), booking.checkOut(), newCheckOut, id)) {
                throw new ConflictException("The room is not available for the extended dates.");
            }
            Booking updated = new Booking(booking.id(), booking.name(), booking.email(),
                    booking.roomId(), booking.roomName(), booking.checkIn(), newCheckOut,
                    booking.guests(), demoTransactionId());
            bookings.put(id, updated);
            try {
                save();
            } catch (IOException exception) {
                bookings.put(id, booking);
                throw exception;
            }
            return new BookingExtension(updated, booking.checkOut());
        }

        private boolean isAvailableExcept(String roomId, LocalDate checkIn, LocalDate checkOut,
                                          String excludedBookingId) {
            return bookings.values().stream()
                    .filter(booking -> !booking.id().equals(excludedBookingId)
                            && booking.roomId().equals(roomId))
                    .noneMatch(booking -> checkIn.isBefore(booking.checkOut())
                            && checkOut.isAfter(booking.checkIn()));
        }

        private void load() throws IOException {
            int lineNumber = 0;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                lineNumber++;
                String[] fields = line.split("\t", -1);
                if (fields.length != 7 && fields.length != 8 && fields.length != 9) {
                    throw new IOException("Invalid booking data at line " + lineNumber + " in " + file);
                }
                try {
                    Booking booking = new Booking(decodeField(fields[0]), decodeField(fields[1]),
                            decodeField(fields[2]), decodeField(fields[3]), decodeField(fields[4]),
                            LocalDate.parse(decodeField(fields[5])),
                            LocalDate.parse(decodeField(fields[6])),
                            fields.length == 9 ? Integer.parseInt(decodeField(fields[7])) : 1,
                            fields.length == 8 ? decodeField(fields[7])
                                    : fields.length == 9 ? decodeField(fields[8])
                                    : legacyTransactionId(decodeField(fields[0])));
                    bookings.put(booking.id(), booking);
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Invalid booking data at line " + lineNumber + " in " + file,
                            exception);
                }
            }
        }

        private void save() throws IOException {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            List<String> lines = new ArrayList<>();
            for (Booking booking : bookings.values()) {
                lines.add(String.join("\t", encodeField(booking.id()), encodeField(booking.name()),
                        encodeField(booking.email()), encodeField(booking.roomId()),
                        encodeField(booking.roomName()), encodeField(booking.checkIn().toString()),
                        encodeField(booking.checkOut().toString()),
                        encodeField(Integer.toString(booking.guests())),
                        encodeField(booking.transactionId())));
            }
            Files.write(temporary, lines, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private static String encodeField(String value) {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(value.getBytes(StandardCharsets.UTF_8));
        }

        private static String decodeField(String value) {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        }
    }

    private static class BadRequestException extends RuntimeException {
        private BadRequestException(String message) {
            super(message);
        }
    }

    private static class NotFoundException extends RuntimeException {
        private NotFoundException(String message) {
            super(message);
        }
    }

    private static class ConflictException extends RuntimeException {
        private ConflictException(String message) {
            super(message);
        }
    }
}
