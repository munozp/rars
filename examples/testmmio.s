.data
readfromio: .word 0xFFFF0000
writetoio:  .word 0xFFFF0020

.text
main:
	lw a1, readfromio
	lw a2, writetoio

loop:
	lw a3, 0(a1)   # Read IO
	slli a3, a3, 1 # Multiply *2
	sw a3, 0(a2)   # Write IO
	
	li a0, 100 # Sleep for 100 ms
	li a7, 32
	ecall

	j loop
