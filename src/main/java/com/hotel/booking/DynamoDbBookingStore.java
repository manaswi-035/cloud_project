package com.hotel.booking;

import com.hotel.booking.HotelBookingApplication.Booking;
import com.hotel.booking.HotelBookingApplication.BookingExtension;
import com.hotel.booking.HotelBookingApplication.BadRequestException;
import com.hotel.booking.HotelBookingApplication.ConflictException;
import com.hotel.booking.HotelBookingApplication.NotFoundException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class DynamoDbBookingStore {
    static final int MAX_TRANSACTION_NIGHTS = 98;

    private final DynamoDbClient client;
    private final String tableName;

    DynamoDbBookingStore(String tableName) {
        this(DynamoDbClient.create(), tableName);
    }

    DynamoDbBookingStore(DynamoDbClient client, String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalStateException("The BOOKINGS_TABLE environment variable is required.");
        }
        this.client = client;
        this.tableName = tableName;
    }

    boolean isAvailable(String roomId, LocalDate checkIn, LocalDate checkOut) {
        QueryRequest request = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("#pk = :pk AND #sk BETWEEN :start AND :end")
                .expressionAttributeNames(Map.of("#pk", "PK", "#sk", "SK"))
                .expressionAttributeValues(Map.of(
                        ":pk", string("ROOM#" + roomId),
                        ":start", string(dayKey(checkIn)),
                        ":end", string(dayKey(checkOut.minusDays(1)))))
                .consistentRead(true)
                .limit(1)
                .build();
        return client.query(request).count() == 0;
    }

    List<Booking> findByEmail(String email) {
        List<Booking> bookings = new ArrayList<>();
        Map<String, AttributeValue> cursor = null;
        do {
            QueryRequest.Builder request = QueryRequest.builder()
                    .tableName(tableName)
                    .keyConditionExpression("#pk = :pk")
                    .expressionAttributeNames(Map.of("#pk", "PK"))
                    .expressionAttributeValues(Map.of(":pk", string(emailPartition(email))))
                    .consistentRead(true)
                    .scanIndexForward(true);
            if (cursor != null && !cursor.isEmpty()) {
                request.exclusiveStartKey(cursor);
            }
            var response = client.query(request.build());
            response.items().stream().map(DynamoDbBookingStore::toBooking).forEach(bookings::add);
            cursor = response.lastEvaluatedKey();
        } while (cursor != null && !cursor.isEmpty());
        return bookings;
    }

    Booking find(String id, String email) {
        Map<String, AttributeValue> item = client.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(bookingKey(id))
                .consistentRead(true)
                .build()).item();
        if (item == null || item.isEmpty()) {
            throw new NotFoundException("Booking not found for that email address.");
        }
        Booking booking = toBooking(item);
        if (!booking.email().equalsIgnoreCase(email)) {
            throw new NotFoundException("Booking not found for that email address.");
        }
        return booking;
    }

    void create(Booking booking) {
        int nights = nights(booking.checkIn(), booking.checkOut());
        requireTransactionLength(nights);

        List<TransactWriteItem> writes = new ArrayList<>();
        writes.add(TransactWriteItem.builder().put(Put.builder()
                .tableName(tableName)
                .item(bookingItem(booking))
                .conditionExpression("attribute_not_exists(PK)")
                .build()).build());
        writes.add(TransactWriteItem.builder().put(Put.builder()
                .tableName(tableName)
                .item(emailItem(booking))
                .conditionExpression("attribute_not_exists(PK)")
                .build()).build());
        addOccupancyPuts(writes, booking.roomId(), booking.checkIn(), booking.checkOut(), booking.id());
        try {
            transact(writes);
        } catch (TransactionCanceledException exception) {
            throw new ConflictException("That room is no longer available for those dates.");
        }
    }

    Booking cancel(String id, String email) {
        Booking booking = find(id, email);
        List<TransactWriteItem> writes = new ArrayList<>();
        for (LocalDate day = booking.checkIn(); day.isBefore(booking.checkOut()); day = day.plusDays(1)) {
            writes.add(TransactWriteItem.builder().delete(Delete.builder()
                    .tableName(tableName)
                    .key(occupancyKey(booking.roomId(), day))
                    .conditionExpression("#bookingId = :bookingId")
                    .expressionAttributeNames(Map.of("#bookingId", "bookingId"))
                    .expressionAttributeValues(Map.of(":bookingId", string(booking.id())))
                    .build()).build());
        }
        writes.add(TransactWriteItem.builder().delete(Delete.builder()
                .tableName(tableName)
                .key(bookingKey(id))
                .conditionExpression("#email = :email")
                .expressionAttributeNames(Map.of("#email", "email"))
                .expressionAttributeValues(Map.of(":email", string(booking.email())))
                .build()).build());
        writes.add(TransactWriteItem.builder().delete(Delete.builder()
                .tableName(tableName)
                .key(emailKey(booking))
                .conditionExpression("#bookingId = :bookingId")
                .expressionAttributeNames(Map.of("#bookingId", "bookingId"))
                .expressionAttributeValues(Map.of(":bookingId", string(booking.id())))
                .build()).build());
        try {
            transact(writes);
        } catch (TransactionCanceledException exception) {
            throw new ConflictException("The booking changed before it could be cancelled. Please try again.");
        }
        return booking;
    }

    BookingExtension extend(String id, String email, LocalDate newCheckOut) {
        Booking booking = find(id, email);
        if (!newCheckOut.isAfter(booking.checkOut())) {
            throw new BadRequestException("The new check-out must be after your current check-out.");
        }
        requireTransactionLength(nights(booking.checkIn(), newCheckOut));

        Booking updated = new Booking(booking.id(), booking.name(), booking.email(),
                booking.roomId(), booking.roomName(), booking.checkIn(), newCheckOut,
                booking.guests(), HotelBookingApplication.demoTransactionId());
        List<TransactWriteItem> writes = new ArrayList<>();
        writes.add(TransactWriteItem.builder().update(Update.builder()
                .tableName(tableName)
                .key(bookingKey(id))
                .updateExpression("SET #checkOut = :checkOut, #transactionId = :transactionId")
                .conditionExpression("#email = :email AND #checkOut = :previousCheckOut")
                .expressionAttributeNames(Map.of(
                        "#email", "email",
                        "#checkOut", "checkOut",
                        "#transactionId", "transactionId"))
                .expressionAttributeValues(Map.of(
                        ":email", string(booking.email()),
                        ":checkOut", string(newCheckOut.toString()),
                        ":previousCheckOut", string(booking.checkOut().toString()),
                        ":transactionId", string(updated.transactionId())))
                .build()).build());
        writes.add(TransactWriteItem.builder().update(Update.builder()
                .tableName(tableName)
                .key(emailKey(booking))
                .updateExpression("SET #checkOut = :checkOut, #transactionId = :transactionId")
                .conditionExpression("#bookingId = :bookingId AND #checkOut = :previousCheckOut")
                .expressionAttributeNames(Map.of(
                        "#bookingId", "bookingId",
                        "#checkOut", "checkOut",
                        "#transactionId", "transactionId"))
                .expressionAttributeValues(Map.of(
                        ":bookingId", string(booking.id()),
                        ":checkOut", string(newCheckOut.toString()),
                        ":previousCheckOut", string(booking.checkOut().toString()),
                        ":transactionId", string(updated.transactionId())))
                .build()).build());
        addOccupancyPuts(writes, booking.roomId(), booking.checkOut(), newCheckOut, booking.id());
        try {
            transact(writes);
        } catch (TransactionCanceledException exception) {
            throw new ConflictException("The room is not available for the extended dates.");
        }
        return new BookingExtension(updated, booking.checkOut());
    }

    private void addOccupancyPuts(List<TransactWriteItem> writes, String roomId,
                                  LocalDate checkIn, LocalDate checkOut, String bookingId) {
        for (LocalDate day = checkIn; day.isBefore(checkOut); day = day.plusDays(1)) {
            Map<String, AttributeValue> item = new HashMap<>(occupancyKey(roomId, day));
            item.put("bookingId", string(bookingId));
            writes.add(TransactWriteItem.builder().put(Put.builder()
                    .tableName(tableName)
                    .item(item)
                    .conditionExpression("attribute_not_exists(PK)")
                    .build()).build());
        }
    }

    private void transact(List<TransactWriteItem> writes) {
        client.transactWriteItems(TransactWriteItemsRequest.builder()
                .transactItems(writes)
                .build());
    }

    private static Map<String, AttributeValue> bookingItem(Booking booking) {
        Map<String, AttributeValue> item = new HashMap<>(bookingKey(booking.id()));
        item.putAll(bookingAttributes(booking));
        return item;
    }

    private static Map<String, AttributeValue> emailItem(Booking booking) {
        Map<String, AttributeValue> item = new HashMap<>(emailKey(booking));
        item.putAll(bookingAttributes(booking));
        return item;
    }

    private static Map<String, AttributeValue> bookingAttributes(Booking booking) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("id", string(booking.id()));
        item.put("bookingId", string(booking.id()));
        item.put("name", string(booking.name()));
        item.put("email", string(booking.email()));
        item.put("roomId", string(booking.roomId()));
        item.put("roomName", string(booking.roomName()));
        item.put("checkIn", string(booking.checkIn().toString()));
        item.put("checkOut", string(booking.checkOut().toString()));
        item.put("guests", number(booking.guests()));
        item.put("transactionId", string(booking.transactionId()));
        return item;
    }

    private static Booking toBooking(Map<String, AttributeValue> item) {
        return new Booking(
                item.get("id").s(),
                item.get("name").s(),
                item.get("email").s(),
                item.get("roomId").s(),
                item.get("roomName").s(),
                LocalDate.parse(item.get("checkIn").s()),
                LocalDate.parse(item.get("checkOut").s()),
                Integer.parseInt(item.get("guests").n()),
                item.get("transactionId").s());
    }

    private static Map<String, AttributeValue> bookingKey(String id) {
        return Map.of("PK", string("BOOKING#" + id), "SK", string("META"));
    }

    private static Map<String, AttributeValue> emailKey(Booking booking) {
        return Map.of("PK", string(emailPartition(booking.email())),
                "SK", string("CHECKIN#" + booking.checkIn() + "#" + booking.id()));
    }

    private static Map<String, AttributeValue> occupancyKey(String roomId, LocalDate day) {
        return Map.of("PK", string("ROOM#" + roomId), "SK", string(dayKey(day)));
    }

    private static String dayKey(LocalDate day) {
        return "DAY#" + day;
    }

    private static String emailPartition(String email) {
        return "EMAIL#" + email.toLowerCase(java.util.Locale.ROOT);
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(int value) {
        return AttributeValue.builder().n(Integer.toString(value)).build();
    }

    private static int nights(LocalDate checkIn, LocalDate checkOut) {
        return Math.toIntExact(ChronoUnit.DAYS.between(checkIn, checkOut));
    }

    private static void requireTransactionLength(int nights) {
        if (nights > MAX_TRANSACTION_NIGHTS) {
            throw new BadRequestException("AWS booking transactions support stays of up to 98 nights.");
        }
    }
}
