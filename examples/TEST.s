.data
readfromio: .word 0xFFFF0000
writetoio:  .word 0xFFFF0020

.text
main:
	lw a1, readfromio
	lw a2, writetoio
loop:
	lw a3, 0(a1) # Read IO
	addi a3, a3, 2
	sw a3, 0(a2 ) # Write IO

     # Sleep for 100 ms
	li a0, 100
	li a7, 32
	ecall

	j	loop

done:
	li	a7, 10
	ecall
