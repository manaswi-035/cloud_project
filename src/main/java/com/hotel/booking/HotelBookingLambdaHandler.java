package com.hotel.booking;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent.RequestContext.Authorizer;
import com.hotel.booking.HotelBookingApplication.BadRequestException;
import com.hotel.booking.HotelBookingApplication.Booking;
import com.hotel.booking.HotelBookingApplication.BookingExtension;
import com.hotel.booking.HotelBookingApplication.ConflictException;
import com.hotel.booking.HotelBookingApplication.NotFoundException;
import com.hotel.booking.HotelBookingApplication.Room;
import com.hotel.booking.HotelBookingApplication.UnauthorizedException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class HotelBookingLambdaHandler
        implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
    private final DynamoDbBookingStore store;

    public HotelBookingLambdaHandler() {
        this(new DynamoDbBookingStore(System.getenv("BOOKINGS_TABLE")));
    }

    HotelBookingLambdaHandler(DynamoDbBookingStore store) {
        this.store = store;
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        try {
            String method = event.getRequestContext().getHttp().getMethod();
            String path = event.getRawPath();
            if (path.equals("/healthz") && method.equals("GET")) {
                return json(200, "{\"status\":\"ok\"}");
            }
            if (path.equals("/api/rooms") && method.equals("GET")) {
                return listRooms(event);
            }
            if (path.equals("/api/bookings") && method.equals("GET")) {
                return listBookings(event);
            }
            if (path.equals("/api/bookings") && method.equals("POST")) {
                return createBooking(event);
            }
            if (path.startsWith("/api/bookings/") && path.endsWith("/extend")
                    && method.equals("POST")) {
                return extendBooking(event, path);
            }
            if (path.startsWith("/api/bookings/") && path.endsWith("/cancel")
                    && method.equals("POST")) {
                return cancelBooking(event, path);
            }
            return json(404, "{\"error\":\"Not found\"}");
        } catch (BadRequestException exception) {
            return json(400, HotelBookingApplication.jsonObject("error", exception.getMessage()));
        } catch (NotFoundException exception) {
            return json(404, HotelBookingApplication.jsonObject("error", exception.getMessage()));
        } catch (ConflictException exception) {
            return json(409, HotelBookingApplication.jsonObject("error", exception.getMessage()));
        } catch (UnauthorizedException exception) {
            return json(401, HotelBookingApplication.jsonObject("error", exception.getMessage()));
        } catch (RuntimeException exception) {
            exception.printStackTrace(System.err);
            return json(500, "{\"error\":\"Unable to process the booking request\"}");
        }
    }

    private APIGatewayV2HTTPResponse listRooms(APIGatewayV2HTTPEvent event) {
        Map<String, String> query = HotelBookingApplication.parseForm(event.getRawQueryString());
        LocalDate checkIn = HotelBookingApplication.parseDate(query.get("checkIn"), "check-in");
        LocalDate checkOut = HotelBookingApplication.parseDate(query.get("checkOut"), "check-out");
        HotelBookingApplication.validateDates(checkIn, checkOut);

        StringBuilder result = new StringBuilder("{\"rooms\":[");
        List<Room> rooms = HotelBookingApplication.ROOMS;
        for (int i = 0; i < rooms.size(); i++) {
            Room room = rooms.get(i);
            if (i > 0) {
                result.append(',');
            }
            result.append("{\"id\":").append(HotelBookingApplication.jsonString(room.id()))
                    .append(",\"name\":").append(HotelBookingApplication.jsonString(room.name()))
                    .append(",\"type\":").append(HotelBookingApplication.jsonString(room.type()))
                    .append(",\"pricePerNight\":").append(room.pricePerNight())
                    .append(",\"capacity\":").append(room.capacity())
                    .append(",\"available\":").append(store.isAvailable(room.id(), checkIn, checkOut))
                    .append('}');
        }
        return json(200, result.append("]}").toString());
    }

    private APIGatewayV2HTTPResponse listBookings(APIGatewayV2HTTPEvent event) {
        String email = authenticatedEmail(event);
        List<Booking> bookings = store.findByEmail(email.trim());
        StringBuilder result = new StringBuilder("{\"bookings\":[");
        for (int i = 0; i < bookings.size(); i++) {
            if (i > 0) {
                result.append(',');
            }
            result.append(HotelBookingApplication.toJson(bookings.get(i)));
        }
        return json(200, result.append("]}").toString());
    }

    private APIGatewayV2HTTPResponse createBooking(APIGatewayV2HTTPEvent event) {
        Map<String, String> form = parseBody(event);
        String name = HotelBookingApplication.required(form, "name", "Guest name");
        String email = authenticatedEmail(event);
        String submittedEmail = HotelBookingApplication.required(form, "email", "Email");
        String roomId = HotelBookingApplication.required(form, "roomId", "Room");
        LocalDate checkIn = HotelBookingApplication.parseDate(form.get("checkIn"), "check-in");
        LocalDate checkOut = HotelBookingApplication.parseDate(form.get("checkOut"), "check-out");
        int guests;
        try {
            guests = Integer.parseInt(HotelBookingApplication.required(form, "guests", "Guest count"));
        } catch (NumberFormatException exception) {
            throw new BadRequestException("Enter a valid guest count.");
        }

        if (!email.equalsIgnoreCase(submittedEmail)) {
            throw new BadRequestException("Booking email must match the signed-in account.");
        }
        if (name.length() > 100 || email.length() > 254
                || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new BadRequestException("Enter a valid name and email address.");
        }
        HotelBookingApplication.validateDates(checkIn, checkOut);
        if (checkIn.isBefore(LocalDate.now())) {
            throw new BadRequestException("Check-in must be today or a future date.");
        }
        Room room = HotelBookingApplication.ROOMS.stream()
                .filter(candidate -> candidate.id().equals(roomId))
                .findFirst().orElseThrow(() -> new BadRequestException("Select a valid room."));
        if (guests < 1 || guests > room.capacity()) {
            throw new BadRequestException("The selected room cannot accommodate that many guests.");
        }

        Booking booking = new Booking(UUID.randomUUID().toString(), name, email, room.id(),
                room.name(), checkIn, checkOut, guests, HotelBookingApplication.demoTransactionId());
        store.create(booking);
        return json(201, "{\"booking\":" + HotelBookingApplication.toJson(booking) + "}");
    }

    private APIGatewayV2HTTPResponse cancelBooking(APIGatewayV2HTTPEvent event, String path) {
        String id = bookingId(path, "/cancel");
        String email = authenticatedEmail(event);
        Booking booking = store.cancel(id, email.trim());
        int amount = HotelBookingApplication.bookingAmount(booking);
        return json(200, "{\"message\":\"Booking cancelled.\",\"originalAmount\":"
                + amount + ",\"cancellationFee\":" + (amount - amount / 2)
                + ",\"refundedAmount\":" + amount / 2
                + ",\"refundTransactionId\":" + HotelBookingApplication.jsonString(demoTransactionId()) + "}");
    }

    private APIGatewayV2HTTPResponse extendBooking(APIGatewayV2HTTPEvent event, String path) {
        String id = bookingId(path, "/extend");
        Map<String, String> form = parseBody(event);
        String email = authenticatedEmail(event);
        LocalDate newCheckOut = HotelBookingApplication.parseDate(form.get("checkOut"), "new check-out");
        Booking previous = store.find(id, email.trim());
        if (!newCheckOut.isAfter(previous.checkOut())) {
            throw new BadRequestException("The new check-out must be after your current check-out.");
        }
        HotelBookingApplication.validateDates(previous.checkIn(), newCheckOut);
        BookingExtension extension = store.extend(id, email.trim(), newCheckOut);
        Booking updated = extension.booking();
        Room room = HotelBookingApplication.roomForBooking(updated);
        int additionalAmount = Math.toIntExact(
                java.time.temporal.ChronoUnit.DAYS.between(extension.previousCheckOut(), newCheckOut)
                        * room.pricePerNight());
        return json(200, "{\"booking\":" + HotelBookingApplication.toJson(updated)
                + ",\"additionalAmount\":" + additionalAmount + "}");
    }

    private static String bookingId(String path, String suffix) {
        String id = path.substring("/api/bookings/".length(), path.length() - suffix.length());
        if (id.isBlank() || id.contains("/")) {
            throw new NotFoundException("Booking not found.");
        }
        return id;
    }

    private static Map<String, String> parseBody(APIGatewayV2HTTPEvent event) {
        String body = event.getBody() == null ? "" : event.getBody();
        if (Boolean.TRUE.equals(event.getIsBase64Encoded())) {
            body = new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
        }
        return HotelBookingApplication.parseForm(body);
    }

    private static String authenticatedEmail(APIGatewayV2HTTPEvent event) {
        Authorizer authorizer = event.getRequestContext().getAuthorizer();
        if (authorizer == null || authorizer.getJwt() == null) {
            throw new UnauthorizedException("Sign in to access your bookings.");
        }
        Map<String, String> claims = authorizer.getJwt().getClaims();
        String email = claims == null ? null : claims.get("email");
        if (email == null || email.isBlank()
                || !"true".equalsIgnoreCase(claims.get("email_verified"))) {
            throw new UnauthorizedException("A verified signed-in email is required.");
        }
        return email;
    }

    private static String demoTransactionId() {
        return "DEMO-" + UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT);
    }

    private static APIGatewayV2HTTPResponse json(int status, String body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put("Cache-Control", "no-store");
        headers.put("X-Content-Type-Options", "nosniff");
        return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(status)
                .withHeaders(headers)
                .withBody(body)
                .build();
    }
}
