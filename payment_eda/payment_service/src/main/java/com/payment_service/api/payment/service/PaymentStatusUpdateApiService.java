package com.payment_service.api.payment.service;

import com.payment_service.message.service.PartitionKeyUtil;
import com.payment_service.message.model.PaymentEventMessage;
import com.payment_service.message.enums.PaymentEventMessageType;
import com.payment_service.domain.outbox.service.OutBoxCommandService;
import com.payment_service.domain.payment.enums.PaymentOrderStatus;
import com.payment_service.domain.payment.model.response.PaymentExecutionResultOutPut;
import com.payment_service.domain.payment.model.response.PaymentOrderStatusOutPut;
import com.payment_service.domain.payment.service.PaymentEventQueryService;
import com.payment_service.domain.payment.service.PaymentOrderQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class PaymentStatusUpdateApiService {

    private final PaymentEventQueryService paymentEventQueryService;
    private final PaymentOrderQueryService paymentOrderQueryService;
    private final OutBoxCommandService outBoxCommandService;

    private final PartitionKeyUtil partitionKeyUtil;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void updatePaymentStatus(PaymentExecutionResultOutPut paymentExecutionResult) {

        PaymentOrderStatus paymentStatus = paymentExecutionResult.getPaymentStatus();

        switch (paymentStatus) {
            case SUCCESS:
                this.updatePaymentStatusToSuccess(paymentExecutionResult);
                break;
            case FAILURE, UNKNOWN:
                this.updatePaymentStatusToFailureOrUnknown(paymentExecutionResult);
                break;
            default: {
                // ERROR Exception
                log.error("!오류! 결제 상태 업그레이드 에러 발생 PaymentStatusUpdateApiService#updatePaymentStatus getOrderId() = {}", paymentExecutionResult.getOrderId());
                throw new IllegalArgumentException("결제 상태 업그레이드 에러 발생 ## 주문 아이디값: " + paymentExecutionResult.getOrderId());
            }
        }
    }

    private void updatePaymentStatusToSuccess(PaymentExecutionResultOutPut paymentExecutionResult) {

        List<PaymentOrderStatusOutPut> paymentOrderStatusList
                = paymentOrderQueryService.selectPaymentOrderStatusListByOrderId(paymentExecutionResult.getOrderId());
        paymentOrderQueryService.insertPaymentOrderHistoryList(paymentOrderStatusList, paymentExecutionResult.getPaymentStatus(), "PAYMENT_CONFIRMATION_DONE");
        paymentOrderQueryService.updatePaymentOrderStatusByOrderId(paymentExecutionResult.getOrderId(), paymentExecutionResult.getPaymentStatus());
        paymentEventQueryService.updatePaymentEventExtraDetails(
                paymentExecutionResult.getOrderId(),
                paymentExecutionResult.getPaymentKey(),
                paymentExecutionResult.getPspRawData(),
                paymentExecutionResult.getExtraDetails().getApprovedAt(),
                true
        );

        String orderId = paymentExecutionResult.getOrderId();
        int partitionKey = partitionKeyUtil.createPartitionKey(orderId.hashCode());
        PaymentEventMessage paymentEventMessage = this.createPaymentEventMessage(orderId, partitionKey);
        outBoxCommandService.insertOutBox(paymentEventMessage);

        eventPublisher.publishEvent(paymentEventMessage);
    }

    private void updatePaymentStatusToFailureOrUnknown(PaymentExecutionResultOutPut paymentExecutionResult) {

        List<PaymentOrderStatusOutPut> paymentOrderStatusList
                = paymentOrderQueryService.selectPaymentOrderStatusListByOrderId(paymentExecutionResult.getOrderId());
        paymentOrderQueryService.insertPaymentOrderHistoryList(paymentOrderStatusList, paymentExecutionResult.getPaymentStatus(), "PAYMENT_CONFIRMATION_DONE");
        paymentOrderQueryService.updatePaymentOrderStatusByOrderId(paymentExecutionResult.getOrderId(), paymentExecutionResult.getPaymentStatus());
        paymentEventQueryService.updatePaymentEventExtraDetails(
                paymentExecutionResult.getOrderId(),
                paymentExecutionResult.getPaymentKey(),
                paymentExecutionResult.getPspRawData(),
                null,
                false
        );
    }

    private PaymentEventMessage createPaymentEventMessage(String orderId, int partitionKey) {
        return PaymentEventMessage.of(
                PaymentEventMessageType.PAYMENT_CONFIRMATION_SUCCESS,
                Map.of("orderId", orderId),
                Map.of("partitionKey", String.valueOf(partitionKey))
        );
    }
}