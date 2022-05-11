.data
readpower: .word 0xFFFF0000
sendcmd:   .word 0xFFFF0060

.text
main:
	lw a1, readpower
	lw a2, sendcmd
loop:
	lw a3, 0(a1) # Read IO
	addi a4, a4, 1000
	sw a4, 0(a2 ) # Write IO

	j	loop

done:
	li	a7, 10
	ecall
